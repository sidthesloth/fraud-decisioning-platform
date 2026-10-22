package com.acme.fraud.api;

import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.decisions.DecisionStore.DecisionPage;
import com.acme.fraud.decisions.DecisionStore.DecisionQuery;
import com.acme.fraud.decisions.DecisionStore.StoredDecision;
import com.acme.fraud.evaluation.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read API over stored decisions. DTOs are explicit; domain types never leak. */
@RestController
@RequestMapping("/api/v1/decisions")
class DecisionsController {

  private final DecisionStore decisions;

  DecisionsController(DecisionStore decisions) {
    this.decisions = decisions;
  }

  @GetMapping("/{id}")
  DecisionResponse get(@PathVariable UUID id) {
    StoredDecision stored =
        decisions
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found"));
    return DecisionResponse.from(stored);
  }

  record StatusResponse(UUID decisionId, String status, boolean evaluationPublished) {}

  @GetMapping("/{id}/status")
  StatusResponse status(@PathVariable UUID id) {
    boolean published =
        decisions
            .evaluationPublished(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found"));
    return new StatusResponse(id, published ? "PUBLISHED" : "PERSISTED", published);
  }

  @GetMapping
  PageResponse<DecisionSummaryResponse> search(
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) Outcome outcome,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    DecisionPage result =
        decisions.search(new DecisionQuery(customerId, outcome, from, to, page, size));
    List<DecisionSummaryResponse> items =
        result.items().stream().map(DecisionSummaryResponse::from).toList();
    return new PageResponse<>(items, page, size, result.total());
  }
}
