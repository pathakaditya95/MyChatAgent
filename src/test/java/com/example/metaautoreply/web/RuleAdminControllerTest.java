package com.example.metaautoreply.web;

import com.example.metaautoreply.AbstractIntegrationTest;
import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class RuleAdminControllerTest extends AbstractIntegrationTest {

	private static final String USER = "test_admin";
	private static final String PASSWORD = "test_admin_password";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private KeywordRuleRepository rules;

	@BeforeEach
	void clean() {
		rules.deleteAll();
	}

	private static String body(String name, String matchType, String keyword,
			String publicReply, String dmText) {
		return """
				{"name":"%s","platform":"IG","triggerType":"COMMENT","matchType":"%s",
				 "keyword":"%s","publicReply":%s,"dmText":%s,"enabled":true,"priority":10}"""
				.formatted(name, matchType, keyword, quote(publicReply), quote(dmText));
	}

	private static String quote(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	private KeywordRule existingRule() {
		return rules.save(new KeywordRule("existing", Platform.IG, TriggerType.COMMENT,
				MatchType.CONTAINS, "price", "public", "dm", false, 20));
	}

	// --- Authentication -----------------------------------------------------------------

	@Test
	void unauthenticatedAccessToTheApiIsRejected() throws Exception {
		mockMvc.perform(get("/api/rules")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON)
				.content(body("x", "CONTAINS", "k", "reply", null)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void wrongCredentialsAreRejected() throws Exception {
		mockMvc.perform(get("/api/rules").with(httpBasic(USER, "wrong-password")))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * The exemption that matters most. Meta cannot authenticate, so a webhook behind Basic auth
	 * would 401, be retried, and eventually have the subscription disabled — the service would
	 * go silent with no obvious cause.
	 */
	@Test
	void theWebhookStaysOpenToUnauthenticatedRequests() throws Exception {
		mockMvc.perform(get("/webhook")
						.param("hub.mode", "subscribe")
						.param("hub.verify_token", "test_verify_token")
						.param("hub.challenge", "xyz"))
				.andExpect(status().isOk());

		// An unsigned POST is refused on its signature (403), never on authentication (401).
		mockMvc.perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void healthStaysOpenForProbes() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	// --- CRUD ---------------------------------------------------------------------------

	@Test
	void createReturns201AndPersistsTheRule() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("price rule", "CONTAINS", "price", "Sent you a DM!", "Details inside")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("price rule"))
				.andExpect(jsonPath("$.enabled").value(true));

		assertThat(rules.findAll()).singleElement()
				.satisfies(r -> assertThat(r.getKeyword()).isEqualTo("price"));
	}

	@Test
	void listReturnsRulesInPriorityOrder() throws Exception {
		rules.save(new KeywordRule("low", Platform.IG, TriggerType.COMMENT, MatchType.CONTAINS,
				"a", "x", null, true, 50));
		rules.save(new KeywordRule("high", Platform.IG, TriggerType.COMMENT, MatchType.CONTAINS,
				"b", "x", null, true, 5));

		mockMvc.perform(get("/api/rules").with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("high"));
	}

	@Test
	void getReturnsOneRuleAnd404ForAnUnknownId() throws Exception {
		KeywordRule rule = existingRule();

		mockMvc.perform(get("/api/rules/" + rule.getId()).with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keyword").value("price"));

		mockMvc.perform(get("/api/rules/999999").with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isNotFound());
	}

	@Test
	void updateReplacesEveryField() throws Exception {
		KeywordRule rule = existingRule();

		mockMvc.perform(put("/api/rules/" + rule.getId()).with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("renamed", "EXACT", "info", "new reply", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("renamed"))
				.andExpect(jsonPath("$.matchType").value("EXACT"))
				.andExpect(jsonPath("$.dmText").doesNotExist());
	}

	@Test
	void toggleFlipsEnabledBothWays() throws Exception {
		KeywordRule rule = existingRule();
		assertThat(rule.isEnabled()).isFalse();

		mockMvc.perform(patch("/api/rules/" + rule.getId() + "/toggle").with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true));

		mockMvc.perform(patch("/api/rules/" + rule.getId() + "/toggle").with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));
	}

	@Test
	void deleteRemovesTheRule() throws Exception {
		KeywordRule rule = existingRule();

		mockMvc.perform(delete("/api/rules/" + rule.getId()).with(httpBasic(USER, PASSWORD)))
				.andExpect(status().isNoContent());

		assertThat(rules.findAll()).isEmpty();
	}

	// --- Validation ----------------------------------------------------------------------

	/** An uncompilable pattern would otherwise fail silently at match time, once per event. */
	@Test
	void anInvalidRegexIsRejectedWithAClearMessage() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("bad regex", "REGEX", "([unclosed", "reply", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
						"valid regular expression")));

		assertThat(rules.findAll()).isEmpty();
	}

	@Test
	void aValidRegexIsAccepted() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("good regex", "REGEX", "^price\\\\b", "reply", null)))
				.andExpect(status().isCreated());
	}

	/** A rule with neither reply matches, consumes the event, and sends nothing. */
	@Test
	void aRuleWithNeitherReplyIsRejected() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("no replies", "CONTAINS", "price", null, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
						"at least one of publicReply or dmText")));
	}

	@Test
	void aBlankKeywordIsRejected() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("blank keyword", "CONTAINS", "   ", "reply", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("keyword")));
	}

	@Test
	void anUnknownEnumValueIsRejectedWithGuidance() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"x","platform":"TWITTER","triggerType":"COMMENT",
								 "matchType":"CONTAINS","keyword":"k","publicReply":"r"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("IG or FB")));
	}

	@Test
	void validationRejectsBeforeTouchingTheDatabase() throws Exception {
		mockMvc.perform(post("/api/rules").with(httpBasic(USER, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("", "CONTAINS", "price", "reply", null)))
				.andExpect(status().isBadRequest());

		assertThat(rules.findAll()).isEmpty();
	}
}
