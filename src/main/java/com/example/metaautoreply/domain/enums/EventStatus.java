package com.example.metaautoreply.domain.enums;

/** Lifecycle of an {@code inbound_event} row as it is drained by the processor. */
public enum EventStatus {
	NEW,
	PROCESSING,
	DONE,
	/** Not actionable: self-reply, no matching rule, opted-out contact, or a non-add verb. */
	SKIPPED,
	FAILED
}
