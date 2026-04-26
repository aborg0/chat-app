# ADR-0001: Layered Backend Boundaries

## Status

Accepted

## Context

The backend contains HTTP routes, business logic modules, and database infrastructure. Without explicit boundaries, routing code can become tightly coupled to persistence details and business logic can become dependent on transport concerns.

## Decision

The backend uses a layered design with these dependency rules:

- API packages may depend on service modules
- API packages may not depend directly on database infrastructure packages, except for the application composition root
- service modules may depend on infrastructure abstractions and utilities, but not on API packages
- infrastructure packages may not depend on service or API packages

`Main.scala` is treated as the composition root and may wire all layers together.

## Consequences

- service code remains reusable outside the HTTP transport layer
- database concerns remain isolated from request handling concerns
- application startup is the single place where cross-layer wiring is allowed
- architecture fitness checks can validate most of the boundary contract automatically
