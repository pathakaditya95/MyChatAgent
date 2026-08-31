package com.example.metaautoreply.repo;

import com.example.metaautoreply.domain.OutboundMessage;
import com.example.metaautoreply.domain.enums.OutboundKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {

	/**
	 * Claim a batch of messages whose retry time has arrived.
	 *
	 * <p>As with the inbound drain, {@code for update skip locked} keeps concurrent
	 * dispatchers off each other's rows, and this must run inside a
	 * {@code @Transactional} method or the locks are released before they are useful.
	 */
	/**
	 * Claim a batch of messages whose retry time has arrived, public replies first.
	 *
	 * <p>The ordering is deliberate and cannot be a plain {@code order by kind}: sorted as
	 * text, {@code PRIVATE_REPLY} comes before {@code PUBLIC_REPLY}, which is the opposite of
	 * what we need. When a comment gets both, the visible reply should land before the DM, so
	 * the commenter sees the acknowledgement first. Hence the explicit ranking.
	 */
	@Query(value = """
			select * from outbound_message
			where status = 'PENDING' and next_attempt_at <= now()
			order by case kind
			           when 'PUBLIC_REPLY' then 0
			           when 'PRIVATE_REPLY' then 1
			           else 2
			         end,
			         next_attempt_at
			limit :limit
			for update skip locked
			""", nativeQuery = true)
	List<OutboundMessage> lockBatch(@Param("limit") int limit);

	/**
	 * Pre-check for the {@code unique (kind, target_id)} constraint.
	 *
	 * <p>Catching the constraint violation instead would poison the surrounding transaction and
	 * roll back the whole batch. Checking first keeps the common redelivery case cheap; the
	 * constraint remains the real guarantee against a concurrent race.
	 */
	boolean existsByKindAndTargetId(OutboundKind kind, String targetId);
}
