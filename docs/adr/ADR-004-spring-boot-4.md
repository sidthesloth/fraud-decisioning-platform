# ADR-004: Spring Boot 4.x baseline (supersedes ADR-001 version clause)

## Status
Accepted

## Context
ADR-001 fixed the baseline at Spring Boot 3.x for reviewer familiarity and
ecosystem maturity. During M1 scaffolding the dependency resolution moved to
Spring Boot 4.1.1, Spring Modulith 2.1.1 and Jackson 3 (tools.jackson), with
the full gate green (84 tests, 0 failures, including both architecture tests
and the ruledomain DSL property tests). Reverting would discard a working,
tested state solely to preserve an earlier assumption.

## Decision
Adopt the Spring Boot 4.1.x line as the baseline, with Spring Modulith 2.1.x
and Jackson 3. ADR-001 is superseded on the Spring Boot version clause only;
its Java 21 and Maven-wrapper decisions remain in force. The Java-version
reasoning of ADR-001 (LTS-first, no preview features) is unchanged and
carries over to the Boot 4 line.

## Consequences
- Reviewer familiarity risk: some evaluators will know Boot 3 better than
  Boot 4. Mitigation: be ready to explain what changed and why the upgrade
  was recorded rather than accidental.
- Jackson 3 moves the API from com.fasterxml.jackson to tools.jackson; the
  condition DSL parser and any future JSON handling must use the Jackson 3
  packages consistently. No mixed-version imports.
- Version numbers must be pinned in the pom (parent + Jackson BOM), never
  floating, so the build is reproducible for reviewers and CI.
- Before submission, re-run the full gate (./mvnw clean verify) and the two
  architecture tests to evidence the upgrade holds; record the result in
  docs/PROGRESS.md.