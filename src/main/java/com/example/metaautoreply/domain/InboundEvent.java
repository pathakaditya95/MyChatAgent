package com.example.metaautoreply.domain;

import com.example.metaautoreply.domain.enums.EventStatus;
import com.example.metaautoreply.domain.enums.Platform;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One raw webhook delivery, already split out of the {@code entry[]} array.
 *
 * <p>Written by the webhook endpoint with nothing but a signature check in front of it,
 * then drained asynchronously by the event processor. Keeping the intake dumb is what
 * lets the endpoint answer Meta inside its timeout.
 */
@Entity
@Table(name = "inbound_event")
@Getter
@Setter
@NoArgsConstructor
public class InboundEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Idempotency key: the comment id, the message mid, or a hash of the payload.
	 * Unique, so a redelivery from Meta collapses into the row already stored.
	 */
	@Column(name = "event_key", nullable = false, unique = true)
	private String eventKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Platform platform;

	/** Free-form rather than an enum: unrecognised shapes are stored as {@code UNKNOWN}. */
	@Column(name = "event_type", nullable = false)
	private String eventType;

	/** The sub-payload as delivered, kept verbatim so Phase 5 can mine it for fixtures. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EventStatus status = EventStatus.NEW;

	@Column(nullable = false)
	private int attempts = 0;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt = Instant.now();

	@Column(name = "processed_at")
	private Instant processedAt;

	public InboundEvent(String eventKey, Platform platform, String eventType, String payload) {
		this.eventKey = eventKey;
		this.platform = platform;
		this.eventType = eventType;
		this.payload = payload;
	}
}
