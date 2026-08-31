package com.example.metaautoreply.delivery.exceptions;

/**
 * Base for every Graph API failure, carrying the detail needed to decide what to do next.
 *
 * <p>Meta returns HTTP 400 for a great many conditions that are not client bugs, so the status
 * alone is not enough — {@code error.code} and {@code error.error_subcode} are what actually
 * separate "try again" from "give up" from "the token is dead".
 */
public abstract class MetaApiException extends RuntimeException {

	private final int httpStatus;
	private final int code;
	private final int subcode;
	private final String body;

	protected MetaApiException(String message, int httpStatus, int code, int subcode, String body) {
		super(message);
		this.httpStatus = httpStatus;
		this.code = code;
		this.subcode = subcode;
		this.body = body;
	}

	public int getHttpStatus() {
		return httpStatus;
	}

	/** Meta's {@code error.code}, or -1 when the body carried none. */
	public int getCode() {
		return code;
	}

	/** Meta's {@code error.error_subcode}, or -1 when absent. */
	public int getSubcode() {
		return subcode;
	}

	/** The raw error body, kept for diagnosing codes we do not yet recognise. */
	public String getBody() {
		return body;
	}
}
