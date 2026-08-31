package com.example.metaautoreply.domain;

import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A user-editable rule: when {@code keyword} matches incoming text on {@code platform},
 * queue {@code publicReply} and/or {@code dmText}.
 *
 * <p>Lowest {@code priority} value wins; the first match ends the search.
 */
@Entity
@Table(name = "keyword_rule")
@Getter
@Setter
@NoArgsConstructor
public class KeywordRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Platform platform;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false)
	private TriggerType triggerType;

	@Enumerated(EnumType.STRING)
	@Column(name = "match_type", nullable = false)
	private MatchType matchType;

	/** Interpreted according to {@link #matchType}. For REGEX this is the pattern source. */
	@Column(nullable = false)
	private String keyword;

	/** Posted on the comment thread. Null means no public reply for this rule. */
	@Column(name = "public_reply")
	private String publicReply;

	/** Sent as a private reply to the commenter. Null means no DM for this rule. */
	@Column(name = "dm_text")
	private String dmText;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(nullable = false)
	private int priority = 100;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public KeywordRule(String name, Platform platform, TriggerType triggerType, MatchType matchType,
			String keyword, String publicReply, String dmText, boolean enabled, int priority) {
		this.name = name;
		this.platform = platform;
		this.triggerType = triggerType;
		this.matchType = matchType;
		this.keyword = keyword;
		this.publicReply = publicReply;
		this.dmText = dmText;
		this.enabled = enabled;
		this.priority = priority;
	}
}
