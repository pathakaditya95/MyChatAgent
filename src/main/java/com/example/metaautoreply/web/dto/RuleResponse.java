package com.example.metaautoreply.web.dto;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;

import java.time.Instant;

/** A rule as returned by the admin API. */
public record RuleResponse(
		Long id,
		String name,
		Platform platform,
		TriggerType triggerType,
		MatchType matchType,
		String keyword,
		String publicReply,
		String dmText,
		boolean enabled,
		int priority,
		Instant createdAt) {

	public static RuleResponse from(KeywordRule rule) {
		return new RuleResponse(rule.getId(), rule.getName(), rule.getPlatform(),
				rule.getTriggerType(), rule.getMatchType(), rule.getKeyword(),
				rule.getPublicReply(), rule.getDmText(), rule.isEnabled(),
				rule.getPriority(), rule.getCreatedAt());
	}
}
