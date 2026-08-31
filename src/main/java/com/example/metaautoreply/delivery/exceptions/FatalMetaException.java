package com.example.metaautoreply.delivery.exceptions;

/**
 * A permanent rejection: bad parameters, missing permissions, or a recipient who cannot be
 * messaged. Retrying would fail identically and only burn rate limit, so the message is failed
 * outright.
 */
public class FatalMetaException extends MetaApiException {

	public FatalMetaException(String message, int httpStatus, int code, int subcode, String body) {
		super(message, httpStatus, code, subcode, body);
	}
}
