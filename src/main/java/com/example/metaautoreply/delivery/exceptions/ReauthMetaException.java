package com.example.metaautoreply.delivery.exceptions;

/**
 * The access token is expired, revoked, or invalid.
 *
 * <p>Kept separate from {@link FatalMetaException} because the remedy is human: regenerate the
 * System User token and restart. Retrying cannot help, and every queued message will fail
 * identically until someone acts — which is why the dispatcher logs this one at ERROR.
 */
public class ReauthMetaException extends MetaApiException {

	public ReauthMetaException(String message, int httpStatus, int code, int subcode, String body) {
		super(message, httpStatus, code, subcode, body);
	}
}
