package com.acme.fraud.api;

import com.acme.fraud.ingestion.DeadLetterReplayer;
import com.acme.fraud.ingestion.DeadLetterReplayer.ReplayResult;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin replay of dead-lettered messages; rate limited. */
@RestController
@RequestMapping("/api/v1/dlq")
class DlqController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final DeadLetterReplayer replayer;
  private final RateLimiter limiter;

  DlqController(DeadLetterReplayer replayer, RateLimiter dlqReplayRateLimiter) {
    this.replayer = replayer;
    this.limiter = dlqReplayRateLimiter;
  }

  record ReplayRequest(List<UUID> ids, Integer limit) {}

  record ReplayResponse(int replayed, int skipped) {}

  @PostMapping("/replay")
  ReplayResponse replay(@RequestBody(required = false) ReplayRequest request) {
    if (!limiter.acquirePermission()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "DLQ replay rate limit hit");
    }
    List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();
    int limit = request == null || request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT || ids.size() > MAX_LIMIT) {
      throw new IllegalArgumentException("limit and ids must be within 1.." + MAX_LIMIT);
    }
    ReplayResult r = replayer.replay(ids, limit);
    return new ReplayResponse(r.replayed(), r.skipped());
  }
}
