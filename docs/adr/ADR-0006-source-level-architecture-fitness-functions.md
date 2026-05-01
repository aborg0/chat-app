# ADR-0006: Bytecode-Level Architecture Fitness Functions (ArchUnit)

## Status

Accepted

## Context

The project needs automated checks that enforce package-boundary and layering rules.

An earlier attempt used source scanning because ArchUnit + ASM support for Java 25 class files was not available at that time.

With ArchUnit 1.4.2, Java 25 compatibility is available, so bytecode-level architecture checks are now viable again.

## Decision

Architecture fitness checks are implemented with ArchUnit 1.4.2 against compiled backend classes.

The test suite enforces explicit dependency rules and computes architecture metrics as executable guardrails.

Current checks include:

- app layer route code must not depend directly on DB infrastructure classes
- core service modules must not depend on app-layer types
- core service modules must not depend on app configuration types
- infrastructure must not depend on app or service modules
- non-app modules must not depend on transport-layer (`zio.http`) types
- explicit module independence constraints between core service modules
- top-level package cycle detection

Current metrics include:

- cross-layer dependency ratio (`crossLayerEdgeCount / totalEdgeCount`)
- maximum package out-degree
- package-level afferent/efferent coupling summary (with instability)

Metric thresholds are part of CI fitness functions and can be tightened incrementally as the design improves.

## Consequences

- architecture rules now use semantic bytecode dependencies rather than text scanning heuristics
- violations provide richer diagnostics (exact classes, methods, and edges)
- architecture quality is tracked both by hard rules and by trendable metrics
- this ADR supersedes the temporary source-scanning approach used before ArchUnit 1.4.2
