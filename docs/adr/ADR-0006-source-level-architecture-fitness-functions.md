# ADR-0006: Source-Level Architecture Fitness Functions

## Status

Accepted

## Context

The project needs automated checks that enforce package-boundary rules. ArchUnit was a natural fit, but the attempted setup failed because its bytecode importer depends on ASM, and the available ASM support did not handle Java 25 class files in this environment.

## Decision

Architecture fitness checks are currently implemented by scanning Scala source files rather than compiled JVM bytecode.

The checks treat both `import` and Scala 3 `export` statements as dependency edges for the enforced package-boundary rules.

## Consequences

- architecture constraints remain enforced despite the Java 25 and ArchUnit compatibility problem
- the current checks are simpler and more transparent than bytecode scanning
- the checks are text-based and therefore narrower than full semantic dependency analysis
- if ArchUnit or another semantic tool becomes viable under the project runtime, this decision can be revisited
