package com.example.metaautoreply.repo;

import com.example.metaautoreply.domain.InboundEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InboundEventRepository extends JpaRepository<InboundEvent, Long> {

	/**
	 * Claim a batch of unprocessed events for this worker.
	 *
	 * <p>{@code for update skip locked} is what makes the processor safe to run in more
	 * than one instance: rows already claimed by another transaction are stepped over
	 * rather than waited on.
	 *
	 * <p>Must be called inside a {@code @Transactional} method. Outside one, the
	 * transaction commits as the query returns and the locks release immediately, which
	 * silently removes the guarantee.
	 */
	@Query(value = """
			select * from inbound_event
			where status = 'NEW'
			order by received_at
			limit :limit
			for update skip locked
			""", nativeQuery = true)
	List<InboundEvent> lockBatch(@Param("limit") int limit);

	/** Idempotency check for webhook intake — Meta redelivers the same event freely. */
	boolean existsByEventKey(String eventKey);

	/** Newest first, for the Phase 5 capture endpoint. */
	List<InboundEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

	/** Distinct event types seen so far, with counts — drives the capture checklist. */
	@Query(value = """
			select event_type as type, count(*) as count
			from inbound_event
			group by event_type
			order by count(*) desc
			""", nativeQuery = true)
	List<EventTypeCount> countByEventType();

	/** Projection for {@link #countByEventType()}. */
	interface EventTypeCount {
		String getType();

		long getCount();
	}
}
