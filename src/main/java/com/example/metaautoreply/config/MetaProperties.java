package com.example.metaautoreply.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything the service needs to talk to Meta, bound from the {@code meta.*} block in
 * {@code application.yml} and ultimately from environment variables.
 *
 * <p>The secrets carry no defaults on purpose. An unset {@code META_APP_SECRET} fails
 * startup loudly rather than quietly signing with an empty key, which would let anyone
 * forge a valid webhook signature.
 */
@Validated
@ConfigurationProperties(prefix = "meta")
public record MetaProperties(

		/**
		 * Facebook App Secret (App Settings → Basic). HMAC key for webhook signatures on
		 * Page deliveries.
		 */
		@NotBlank String appSecret,

		/**
		 * Instagram App Secret, from the Instagram product settings — a <em>different</em>
		 * value from {@link #appSecret} when the app uses Instagram API with Instagram Login.
		 *
		 * <p>Meta signs each delivery with the secret belonging to the product it came from,
		 * so an app serving both networks has two valid signing keys. Optional: leave unset
		 * for a Page-only app.
		 */
		String instagramAppSecret,

		/** Shared string echoed back during the {@code GET /webhook} subscription handshake. */
		@NotBlank String verifyToken,

		/** System User access token. Sent as a bearer token, never as a query parameter. */
		@NotBlank String accessToken,

		/** Numeric Facebook Page ID. Also used to recognise and drop our own replies. */
		@NotBlank String pageId,

		/** Numeric Instagram Business Account ID. Same self-reply role as {@link #pageId}. */
		@NotBlank String igUserId,

		@NotBlank String graphVersion,

		@NotBlank String graphBaseUrl,

		/** When true, no live Graph API calls are made. Defaults to true so development is safe. */
		boolean dryRun) {

	/**
	 * Fails startup if any required value is missing or is an unresolved placeholder.
	 *
	 * <p>This guard exists because {@code @ConfigurationProperties} binding does not behave
	 * like {@code @Value}: when a placeholder such as {@code ${META_APP_SECRET}} cannot be
	 * resolved, the binder leaves the <em>literal text</em> in place rather than throwing, and
	 * {@code @NotBlank} is satisfied by it. Without this check the service starts happily and
	 * signs webhooks with the constant string "${META_APP_SECRET}" — a value published in this
	 * repository, so anyone could forge a valid signature. Failing loudly is the only safe
	 * behaviour.
	 */
	public MetaProperties {
		requireResolved("META_APP_SECRET", "meta.app-secret", appSecret);
		// Optional, but if supplied it must be a real value rather than a stray placeholder.
		if (instagramAppSecret != null && !instagramAppSecret.isBlank()) {
			requireResolved("META_INSTAGRAM_APP_SECRET", "meta.instagram-app-secret", instagramAppSecret);
		}
		requireResolved("META_VERIFY_TOKEN", "meta.verify-token", verifyToken);
		requireResolved("META_ACCESS_TOKEN", "meta.access-token", accessToken);
		requireResolved("META_PAGE_ID", "meta.page-id", pageId);
		requireResolved("META_IG_USER_ID", "meta.ig-user-id", igUserId);
		requireResolved("META_GRAPH_VERSION", "meta.graph-version", graphVersion);
		requireResolved("META_GRAPH_BASE_URL", "meta.graph-base-url", graphBaseUrl);
	}

	private static void requireResolved(String envVar, String property, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					"Missing required configuration '%s'. Set the %s environment variable."
							.formatted(property, envVar));
		}
		if (value.startsWith("${") && value.endsWith("}")) {
			throw new IllegalStateException(
					("Configuration '%s' resolved to the literal placeholder %s, which means %s is "
							+ "not set. Refusing to start rather than run with a publicly known secret.")
							.formatted(property, value, envVar));
		}
	}
}
