package com.acme.fraud.ingestion;

import java.util.List;
import java.util.UUID;

/** Re-queues dead-lettered messages (fraud.dlq.v1) onto the input topic via the outbox. */
public interface DeadLetterReplayer {

  /**
   * Replays unreplayed dead letters, oldest first. When {@code ids} is empty, up to {@code limit}
   * dead letters are picked; otherwise exactly those ids.
   */
  ReplayResult replay(List<UUID> ids, int limit);

  /** {@code skipped} are dead letters whose original message is not valid JSON or unknown. */
  record ReplayResult(int replayed, int skipped) {}
}
