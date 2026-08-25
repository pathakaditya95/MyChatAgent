package com.example.metaautoreply.domain;

import com.example.metaautoreply.domain.enums.OutboundKind;
import com.example.metaautoreply.domain.enums.OutboundStatus;
import com.example.metaautoreply.domain.enums.Platform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A message queued for delivery to Meta, drained by the outbound dispatcher.
 *
 * <p>The {@code (kind, target_id)} unique constraint is a compliance invariant: it enforces
 * Meta's one-private-reply-per-comment rule in the database, so a redelivered event or a
 * second dispatcher thread cannot produce a duplicate send. Never remove it.
 */
@Entity
@Table(name = "outbound_message", uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "target_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OutboundMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OutboundKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Platform platform;

	/** The comment id for replies, or the PSID/IGSID for a plain DM. */
	@Column(name = "target_id", nullable = false)
	private String targetId;

	@Column(nullable = false)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OutboundStatus status = OutboundStatus.PENDING;

	@Column(nullable = false)
	private int attempts = 0;

	/** Drives the retry backoff. The dispatcher only picks up rows whose time has come. */
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt = Instant.now();

	@Column(name = "last_error")
	private String lastError;

	/** The id Meta returns on a successful send. Null until then. */
	@Column(name = "provider_msg_id")
	private String providerMsgId;

	/** The event that produced this message. Nullable so messages can be queued by hand. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_event_id")
	private InboundEvent sourceEvent;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "sent_at")
	private Instant sentAt;

	public OutboundMessage(OutboundKind kind, Platform platform, String targetId, String body,
			InboundEvent sourceEvent) {
		this.kind = kind;
		this.platform = platform;
		this.targetId = targetId;
		this.body = body;
		this.sourceEvent = sourceEvent;
	}
}
