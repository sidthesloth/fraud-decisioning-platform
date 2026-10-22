package com.acme.fraud.api;

import com.acme.fraud.alert.AlertStatus;
import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.alert.AlertStore.Alert;
import com.acme.fraud.alert.AlertStore.AlertPage;
import com.acme.fraud.alert.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Alert queue and disposition. Mutations use the alert version as ETag / If-Match. */
@RestController
@RequestMapping("/api/v1/alerts")
class AlertsController {

  private final AlertStore alerts;

  AlertsController(AlertStore alerts) {
    this.alerts = alerts;
  }

  record AlertResponse(
      UUID alertId,
      UUID decisionId,
      String severity,
      String status,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    static AlertResponse from(Alert a) {
      return new AlertResponse(
          a.alertId(),
          a.decisionId(),
          a.severity().name(),
          a.status().name(),
          a.createdAt(),
          a.updatedAt(),
          a.version());
    }
  }

  record DispositionRequest(@NotNull AlertStatus status, @Size(max = 1000) String note) {}

  private static String etag(long version) {
    return "\"" + version + "\"";
  }

  @GetMapping("/{id}")
  ResponseEntity<AlertResponse> get(@PathVariable UUID id) {
    Alert a =
        alerts
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    return ResponseEntity.ok().eTag(etag(a.version())).body(AlertResponse.from(a));
  }

  @GetMapping
  PageResponse<AlertResponse> search(
      @RequestParam(required = false) AlertStatus status,
      @RequestParam(required = false) Severity severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    AlertPage result = alerts.search(status, severity, page, size);
    List<AlertResponse> items = result.items().stream().map(AlertResponse::from).toList();
    return new PageResponse<>(items, page, size, result.total());
  }

  @PostMapping("/{id}/disposition")
  ResponseEntity<AlertResponse> disposition(
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody DispositionRequest body,
      Authentication auth) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.PRECONDITION_REQUIRED, "If-Match header with the alert ETag is required");
    }
    long version;
    try {
      version = Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("If-Match must be an alert ETag");
    }
    Alert a = alerts.transition(id, body.status(), auth.getName(), body.note(), version);
    return ResponseEntity.ok().eTag(etag(a.version())).body(AlertResponse.from(a));
  }
}
