package com.example.metaautoreply.web;

import com.example.metaautoreply.config.AdminProperties;
import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.config.SecurityConfig;
import com.example.metaautoreply.ingest.WebhookIngestService;
import com.example.metaautoreply.security.SignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice. Uses the real {@link SignatureVerifier} so the HTTP contract and the
 * crypto are exercised together; only the ingest service is mocked, which lets us force
 * an internal failure and assert the endpoint still acknowledges.
 *
 * <p>The real {@link SecurityConfig} is imported deliberately. Without it {@code @WebMvcTest}
 * applies Spring Security's defaults — everything authenticated, CSRF enforced — and the
 * webhook tests fail with 401 and 403. Importing it means this slice also proves the
 * exemption that keeps Meta able to reach us.
 */
@WebMvcTest(WebhookController.class)
@Import({SignatureVerifier.class, SecurityConfig.class, WebhookControllerTest.Config.class})
class WebhookControllerTest {

	private static final String VERIFY_TOKEN = "test_verify_token";
	private static final String BODY = "{\"object\":\"page\",\"entry\":[]}";

	@TestConfiguration
	static class Config {
		@Bean
		AdminProperties adminProperties() {
			return new AdminProperties("test_admin", "test_admin_password");
		}

		@Bean
		MetaProperties metaProperties() {
			return new MetaProperties("test_app_secret", null, VERIFY_TOKEN, "token", "page", "ig",
					"v21.0", "https://graph.facebook.com", true);
		}
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private SignatureVerifier signatureVerifier;
	@MockitoBean
	private WebhookIngestService ingestService;

	// --- GET /webhook : subscription handshake ------------------------------------------

	@Test
	void echoesTheChallengeAsPlainText() throws Exception {
		mockMvc.perform(get("/webhook")
						.param("hub.mode", "subscribe")
						.param("hub.verify_token", VERIFY_TOKEN)
						.param("hub.challenge", "xyz"))
				.andExpect(status().isOk())
				// Must be bare text. JSON-quoting the challenge makes Meta refuse the subscription.
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
				.andExpect(content().string("xyz"));
	}

	@Test
	void rejectsAWrongVerifyToken() throws Exception {
		mockMvc.perform(get("/webhook")
						.param("hub.mode", "subscribe")
						.param("hub.verify_token", "wrong")
						.param("hub.challenge", "xyz"))
				.andExpect(status().isForbidden());
	}

	@Test
	void rejectsAModeOtherThanSubscribe() throws Exception {
		mockMvc.perform(get("/webhook")
						.param("hub.mode", "unsubscribe")
						.param("hub.verify_token", VERIFY_TOKEN)
						.param("hub.challenge", "xyz"))
				.andExpect(status().isForbidden());
	}

	// --- POST /webhook : event intake ---------------------------------------------------

	@Test
	void acceptsAValidlySignedDelivery() throws Exception {
		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signatureVerifier.sign(BODY))
						.content(BODY))
				.andExpect(status().isOk());

		then(ingestService).should().ingest(BODY);
	}

	@Test
	void rejectsAnInvalidSignatureAndDoesNotIngest() throws Exception {
		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
						.content(BODY))
				.andExpect(status().isForbidden());

		then(ingestService).should(never()).ingest(anyString());
	}

	@Test
	void rejectsADeliveryWithNoSignatureHeader() throws Exception {
		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.content(BODY))
				.andExpect(status().isForbidden());

		then(ingestService).should(never()).ingest(anyString());
	}

	/**
	 * The important one. Once the signature checks out we must acknowledge, even if our own
	 * processing throws — a non-200 makes Meta retry and eventually disable the subscription.
	 */
	@Test
	void stillReturns200WhenIngestThrows() throws Exception {
		given(ingestService.ingest(anyString())).willThrow(new RuntimeException("boom"));

		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signatureVerifier.sign(BODY))
						.content(BODY))
				.andExpect(status().isOk());
	}
}
