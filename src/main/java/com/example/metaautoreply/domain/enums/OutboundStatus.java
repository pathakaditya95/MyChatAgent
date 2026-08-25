package com.example.metaautoreply.domain.enums;

/** Lifecycle of an {@code outbound_message} row as it is drained by the dispatcher. */
public enum OutboundStatus {
	PENDING,
	SENDING,
	SENT,
	/** Permanently rejected by Meta, or the token needs regenerating. Not retried. */
	FAILED,
	/** Retried up to {@code autoreply.max-send-attempts} without success. Given up on. */
	ABANDONED
}
