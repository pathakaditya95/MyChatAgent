package com.example.metaautoreply.repo;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KeywordRuleRepository extends JpaRepository<KeywordRule, Long> {

	/**
	 * Candidate rules for an event, best first. Ordering is part of the contract: the matcher
	 * takes the first hit, so priority decides which rule wins when several would match.
	 *
	 * <p>Backed by {@code idx_keyword_rule_lookup}.
	 */
	List<KeywordRule> findByEnabledTrueAndPlatformAndTriggerTypeOrderByPriorityAsc(
			Platform platform, TriggerType triggerType);
}
