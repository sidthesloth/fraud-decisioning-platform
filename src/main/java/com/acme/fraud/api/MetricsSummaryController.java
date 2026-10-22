package com.acme.fraud.api;

import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.evaluation.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Small roll-up of decision outcomes over a recent window. */
@RestController
@RequestMapping("/api/v1/metrics-summary")
class MetricsSummaryController {

  private final DecisionStore decisions;

  MetricsSummaryController(DecisionStore decisions) {
    this.decisions = decisions;
  }

  record Summary(int windowHours, long total, Map<Outcome, Long> byOutcome) {}

  @GetMapping
  Summary summary(@RequestParam(defaultValue = "24") int hours) {
    if (hours < 1 || hours > 24 * 31) {
      throw new IllegalArgumentException("hours must be within 1..744");
    }
    Map<Outcome, Long> counts =
        decisions.countByOutcome(Instant.now().minus(Duration.ofHours(hours)));
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    return new Summary(hours, total, counts);
  }
}
