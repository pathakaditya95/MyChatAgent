package com.example.metaautoreply.delivery.exceptions;

/**
 * A transient failure — a 5xx, or one of Meta's rate-limit and temporary-error codes. Worth
 * sending again after a backoff, and the only exception Resilience4j is configured to retry.
 */
public class RetryableMetaException extends MetaApiException {

	public RetryableMetaException(String message, int httpStatus, int code, int subcode, String body) {
		super(message, httpStatus, code, subcode, body);
	}
}
