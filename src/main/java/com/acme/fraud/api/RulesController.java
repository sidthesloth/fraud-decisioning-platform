package com.acme.fraud.api;

import com.acme.fraud.ruledomain.ConditionParser;
import com.acme.fraud.ruledomain.RuleAction;
import com.acme.fraud.ruledomain.RuleCatalog;
import com.acme.fraud.ruledomain.RuleCatalog.StoredVersion;
import com.acme.fraud.ruledomain.RuleDraft;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rule catalog. Reads: analyst. Writes: admin, rate limited (rule changes are sensitive). */
@RestController
@RequestMapping("/api/v1/rules")
class RulesController {

  private final RuleCatalog catalog;
  private final RateLimiter adminLimiter;
  private final ConditionParser parser = new ConditionParser();
  private final JsonMapper json = JsonMapper.builder().build();

  RulesController(RuleCatalog catalog, RateLimiter ruleAdminRateLimiter) {
    this.catalog = catalog;
    this.adminLimiter = ruleAdminRateLimiter;
  }

  record VersionRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 1000) String description,
      @NotNull Integer priority,
      @NotNull JsonNode condition,
      @NotNull @Min(0) @PositiveOrZero Integer score,
      @NotBlank @Size(max = 100) String reasonCode,
      Instant effectiveFrom,
      Instant effectiveTo) {}

  record CreateRuleRequest(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String ruleId,
      @Valid @NotNull VersionRequest version) {}

  record VersionResponse(
      String ruleId,
      int version,
      String name,
      String description,
      int priority,
      JsonNode condition,
      int score,
      String reasonCode,
      boolean enabled,
      Instant effectiveFrom,
      Instant effectiveTo) {}

  private VersionResponse toResponse(StoredVersion s) {
    var v = s.version();
    return new VersionResponse(
        v.ruleId(),
        v.version(),
        v.name(),
        v.description(),
        v.priority(),
        json.readTree(s.conditionJson()),
        v.action().scoreContribution(),
        v.action().reasonCode(),
        v.enabled(),
        v.effectiveFrom(),
        v.effectiveTo());
  }

  private RuleDraft toDraft(VersionRequest r) {
    // validates the DSL; RuleDslException is mapped to 400
    var condition = parser.parse(r.condition());
    return new RuleDraft(
        r.name(),
        r.description(),
        r.priority(),
        condition,
        new RuleAction(r.score(), r.reasonCode()),
        r.effectiveFrom(),
        r.effectiveTo());
  }

  private void throttle() {
    if (!adminLimiter.acquirePermission()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rule admin rate limit hit");
    }
  }

  @GetMapping
  List<VersionResponse> list() {
    return catalog.latestVersions().stream().map(this::toResponse).toList();
  }

  @GetMapping("/{ruleId}/versions")
  List<VersionResponse> versions(@PathVariable String ruleId) {
    List<StoredVersion> all = catalog.versions(ruleId);
    if (all.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
    }
    return all.stream().map(this::toResponse).toList();
  }

  @PostMapping
  ResponseEntity<VersionResponse> create(@Valid @RequestBody CreateRuleRequest req) {
    throttle();
    RuleDraft draft = toDraft(req.version());
    StoredVersion created =
        catalog.create(req.ruleId(), draft, req.version().condition().toString());
    return ResponseEntity.created(URI.create("/api/v1/rules/" + req.ruleId() + "/versions"))
        .body(toResponse(created));
  }

  @PostMapping("/{ruleId}/versions")
  ResponseEntity<VersionResponse> newVersion(
      @PathVariable String ruleId, @Valid @RequestBody VersionRequest req) {
    throttle();
    RuleDraft draft = toDraft(req);
    StoredVersion created = catalog.addVersion(ruleId, draft, req.condition().toString());
    return ResponseEntity.created(URI.create("/api/v1/rules/" + ruleId + "/versions"))
        .body(toResponse(created));
  }

  @PostMapping("/{ruleId}/versions/{version}:activate")
  VersionResponse activate(@PathVariable String ruleId, @PathVariable int version) {
    throttle();
    return toResponse(catalog.setEnabled(ruleId, version, true));
  }

  @PostMapping("/{ruleId}/versions/{version}:deactivate")
  VersionResponse deactivate(@PathVariable String ruleId, @PathVariable int version) {
    throttle();
    return toResponse(catalog.setEnabled(ruleId, version, false));
  }
}
