package com.example.metaautoreply.domain.enums;

/** What kind of message is queued for delivery. */
public enum OutboundKind {
	/** A visible reply on the comment thread. */
	PUBLIC_REPLY,
	/** A DM sent in response to a comment. Limited to one per comment. */
	PRIVATE_REPLY,
	/** A direct message not triggered by a comment. Subject to the 24-hour window. */
	DM
}
