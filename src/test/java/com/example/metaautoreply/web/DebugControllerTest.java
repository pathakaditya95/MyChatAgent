package com.example.metaautoreply.web;

import com.example.metaautoreply.IntegrationTest;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.repo.InboundEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The debug endpoint exposes raw payloads — commenter ids, usernames, message text — so the
 * profile guard is a privacy control, not a convenience. Both halves are tested: that it
 * works under the {@code debug} profile, and that it is absent without it.
 *
 * <p>From Phase 9 the endpoints also sit behind HTTP Basic, so these tests authenticate.
 * {@code @WithMockUser} is what separates "404 because the endpoint does not exist" from
 * "401 because we did not log in" — without it the negative test would pass for the wrong
 * reason.
 */
class DebugControllerTest {

	@Nested
	@IntegrationTest
	@AutoConfigureMockMvc
	@ActiveProfiles({"test", "debug"})
	@WithMockUser
	class WithDebugProfile {

		@Autowired
		private MockMvc mockMvc;
		@Autowired
		private InboundEventRepository events;

		@BeforeEach
		void seed() {
			events.deleteAll();
			events.save(new InboundEvent("dbg_1", Platform.IG, "COMMENT",
					"{\"field\":\"comments\",\"value\":{\"id\":\"c1\",\"text\":\"hi\"}}"));
			events.save(new InboundEvent("dbg_2", Platform.FB, "MESSAGE",
					"{\"message\":{\"mid\":\"m1\"}}"));
		}

		@Test
		void listsRecentEventsWithPayloadAsRealJson() throws Exception {
			mockMvc.perform(get("/debug/events").param("limit", "50"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2))
					// Spliced back in as a JSON object, not an escaped string — this is what
					// makes the output directly copyable into a fixture.
					.andExpect(jsonPath("$[?(@.eventKey=='dbg_1')].payload.value.id").value("c1"));
		}

		@Test
		void limitIsHonoured() throws Exception {
			mockMvc.perform(get("/debug/events").param("limit", "1"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(1));
		}

		@Test
		void limitIsClampedRatherThanTrusted() throws Exception {
			mockMvc.perform(get("/debug/events").param("limit", "0"))
					.andExpect(status().isOk());
			mockMvc.perform(get("/debug/events").param("limit", "100000"))
					.andExpect(status().isOk());
		}

		@Test
		void reportsCountsPerEventType() throws Exception {
			mockMvc.perform(get("/debug/events/types"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.COMMENT").value(1))
					.andExpect(jsonPath("$.MESSAGE").value(1));
		}
	}

	@Nested
	@IntegrationTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	@WithMockUser
	class WithoutDebugProfile {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void debugEndpointDoesNotExist() throws Exception {
			mockMvc.perform(get("/debug/events")).andExpect(status().isNotFound());
		}

		@Test
		void debugTypesEndpointDoesNotExist() throws Exception {
			mockMvc.perform(get("/debug/events/types")).andExpect(status().isNotFound());
		}
	}
}
