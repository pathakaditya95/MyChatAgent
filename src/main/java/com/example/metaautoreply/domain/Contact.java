package com.example.metaautoreply.domain;

import com.example.metaautoreply.domain.enums.Platform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Someone we have interacted with, scoped to one platform.
 *
 * <p>Carries two compliance-critical fields: {@code lastInteractionAt} backs the 24-hour
 * messaging window, and {@code optedOut} suppresses all future sends once someone has
 * said stop or unsubscribe.
 */
@Entity
@Table(name = "contact", uniqueConstraints = @UniqueConstraint(columnNames = {"platform", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Contact {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Platform platform;

	/** The PSID (Facebook) or IGSID (Instagram). Opaque, and scoped per app. */
	@Column(name = "external_id", nullable = false)
	private String externalId;

	/** Instagram supplies this; Facebook generally does not. */
	private String username;

	@Column(name = "last_interaction_at")
	private Instant lastInteractionAt;

	@Column(name = "opted_out", nullable = false)
	private boolean optedOut = false;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public Contact(Platform platform, String externalId, String username) {
		this.platform = platform;
		this.externalId = externalId;
		this.username = username;
	}
}
