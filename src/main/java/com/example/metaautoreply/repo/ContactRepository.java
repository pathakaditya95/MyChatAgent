package com.example.metaautoreply.repo;

import com.example.metaautoreply.domain.Contact;
import com.example.metaautoreply.domain.enums.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

	/** Matches the {@code unique (platform, external_id)} constraint — used for upserts. */
	Optional<Contact> findByPlatformAndExternalId(Platform platform, String externalId);
}
