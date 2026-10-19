# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Spring Boot 3.4.0 / Java 22 expert service on port 8091 with Gradle Kotlin DSL.
- PostgreSQL `eia_cession` schema and `cession` table with book, amount, and status constraints.
- Internal `/internal/v1/cessions` list, get, create, and status-transition endpoints.
- DIRECT-book, non-negative amount, and SPEC 11.9.3 cession lifecycle invariant enforcement.
- JUnit controller and service tests.
