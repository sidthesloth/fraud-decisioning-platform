# ADR-001: Java 21 + Spring Boot + Maven wrapper

## Status
Accepted — Spring Boot version clause superseded by ADR-004 (Boot 4.1.x).
Java 21 and Maven-wrapper decisions remain in force.

## Decision
Java 21 (`--release 21`, no preview/incubator features), Spring Boot (see ADR-004), Maven with committed wrapper.

## Context / Consequences
Java 21 is LTS with the most mature toolchain, container-image, and security-scanning support.
Gradle Kotlin DSL was rejected for this artifact: Maven constrains AI-generated build logic,
maximizes reviewer familiarity, and the speed gap is irrelevant versus Testcontainers startup time.
Upgrade to Java 25 only after a full dependency-matrix verification; never chase non-LTS.
