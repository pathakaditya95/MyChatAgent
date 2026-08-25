package com.example.metaautoreply.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the configuration invariant that caught us once already.
 *
 * <p>{@code @ConfigurationProperties} binding leaves an unresolvable placeholder as literal
 * text instead of throwing, and {@code @NotBlank} accepts it. The service then signs webhooks
 * with the constant "${META_APP_SECRET}" — a value anyone can read in this repository. These
 * tests exist so that behaviour can never come back silently.
 */
class MetaPropertiesTest {

	private static MetaProperties withAppSecret(String appSecret) {
		return new MetaProperties(appSecret, null, "verify", "token", "page", "ig",
				"v21.0", "https://graph.facebook.com", true);
	}

	@Test
	void acceptsFullyResolvedConfiguration() {
		assertThatCode(() -> withAppSecret("a-real-secret")).doesNotThrowAnyException();
	}

	@Test
	void rejectsAnUnresolvedPlaceholder() {
		assertThatThrownBy(() -> withAppSecret("${META_APP_SECRET}"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("META_APP_SECRET")
				.hasMessageContaining("Refusing to start");
	}

	@Test
	void rejectsBlankAndNullValues() {
		assertThatThrownBy(() -> withAppSecret("")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> withAppSecret("   ")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> withAppSecret(null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void guardsEveryRequiredFieldNotJustTheAppSecret() {
		assertThatThrownBy(() -> new MetaProperties("secret", null, "${META_VERIFY_TOKEN}", "token",
				"page", "ig", "v21.0", "https://graph.facebook.com", true))
				.hasMessageContaining("META_VERIFY_TOKEN");

		assertThatThrownBy(() -> new MetaProperties("secret", null, "verify", "${META_ACCESS_TOKEN}",
				"page", "ig", "v21.0", "https://graph.facebook.com", true))
				.hasMessageContaining("META_ACCESS_TOKEN");

		assertThatThrownBy(() -> new MetaProperties("secret", null, "verify", "token",
				"${META_PAGE_ID}", "ig", "v21.0", "https://graph.facebook.com", true))
				.hasMessageContaining("META_PAGE_ID");

		assertThatThrownBy(() -> new MetaProperties("secret", null, "verify", "token",
				"page", "${META_IG_USER_ID}", "v21.0", "https://graph.facebook.com", true))
				.hasMessageContaining("META_IG_USER_ID");
	}

	/** A value that merely contains a brace is fine — only a whole-string placeholder is bogus. */
	@Test
	void doesNotRejectLegitimateValuesContainingBraces() {
		assertThatCode(() -> withAppSecret("secret-with-{braces}-inside")).doesNotThrowAnyException();
	}

	@Test
	void dryRunIsCarriedThrough() {
		assertThat(withAppSecret("s").dryRun()).isTrue();
	}
}
