# ADR-002: Java 25 LTS + Spring Boot 4.1

**Status:** Accepted (2026-09-26)

## Context
We need a mature, strongly typed ecosystem with first-class transactions, HTTP clients, resilience, observability, and testing, maintained for years.

## Decision
Java 25 (LTS) with Spring Boot 4.1: Spring MVC on **virtual threads**, `JdbcClient`, Flyway, Micrometer + OpenTelemetry, Resilience4j, Gradle (Kotlin DSL).

## Alternatives
| Option | Pros | Cons |
|---|---|---|
| **Java + Spring Boot** | Deep fintech adoption, excellent transaction/JDBC support, virtual threads make blocking PSP calls cheap, huge talent pool | Heavier memory footprint than Go |
| Kotlin + Spring | Concise, null-safety | Smaller pool; same runtime anyway |
| Go | Small binaries, simple concurrency | Less batteries-included for transactions, validation, and resilience; more hand-rolled code |
| TypeScript / NestJS | Fast iteration | Weaker numeric/type guarantees for money; single-threaded event loop needs care with CPU work |

## Consequences
- Blocking-style code (easy to reason about) scales thanks to virtual threads. DB pool size stays the real concurrency limiter.
- Spring Boot 4 uses Jackson 3 (`tools.jackson.*`) and modular starters.
- JDK 25 is required locally; the Gradle toolchain enforces it.
