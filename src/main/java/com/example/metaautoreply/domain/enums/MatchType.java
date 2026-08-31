package com.example.metaautoreply.domain.enums;

/** How a rule's keyword is compared against incoming text. All matching is case-insensitive. */
public enum MatchType {
	EXACT,
	CONTAINS,
	REGEX
}
