package com.example.metaautoreply.engine;

import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;

import java.time.Instant;

/**
 * A webhook event reduced to the handful of facts the engine actually needs, with the
 * per-platform field names resolved away.
 *
 * @param platform       which network the event came from
 * @param triggerType    comment or message
 * @param eventKey       comment id, or message mid
 * @param senderId       commenter PSID / IGSID
 * @param senderUsername Instagram username or Facebook display name; null when absent
 * @param text           comment or message body; never blank by construction
 * @param parentMediaId  parent media id (IG) or post id (FB); null when absent
 * @param fromSelf       true when we sent this ourselves — must never be replied to
 * @param occurredAt     when Meta says it happened, or null when the payload omits it
 */
public record NormalizedEvent(
		Platform platform,
		TriggerType triggerType,
		String eventKey,
		String senderId,
		String senderUsername,
		String text,
		String parentMediaId,
		boolean fromSelf,
		Instant occurredAt) {
}
