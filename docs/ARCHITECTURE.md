Architecture rules and automated checks
====================================

Purpose
-------
These rules help maintain a clear layered architecture for the backend and catch regressions early via automated tests.

Related documents:
- `docs/SYSTEM_SPECIFICATION.md` captures the current system design in a consultable form.
- `docs/adr/README.md` indexes the architectural decisions that shape the current implementation.

Enforced Rules
--------------
- API layer (package `com.example.app`) must not depend directly on database infrastructure (`com.example.infrastructure.db`). API code must call into service modules instead.
- Service modules (example: `com.example.messaging`, `com.example.chapters`, `com.example.auth`, `com.example.sessions`) must not depend on the API layer (`com.example.app`). Services are lower-level than API and should be reusable.
- Infrastructure DB code (`com.example.infrastructure.db`) must not depend on service or API packages. The DB layer is the lowest-level and should not reference higher layers.

Rationale
---------
- Prevents tight coupling between HTTP routes and persistence implementation.
- Keeps service business logic independent from transport concerns.
- Makes it easier to test, maintain, and evolve each layer independently.

How the tests work
------------------
The tests live in backend/src/test/scala/com/example/architecture/ArchitectureSpec.scala.

They enforce the package-boundary rules by scanning backend Scala source files for dependency edges introduced by both `import` and Scala 3 `export` statements.

We do not currently use ArchUnit's bytecode scanner here. ArchUnit depends on ASM for class parsing, and the bundled ASM version in the attempted setup does not support Java 25 class files (major version 69). That made `ClassFileImporter` fail at runtime, so the checks were implemented at the source level instead.

Running locally
---------------
From the project root run:

```bash
sbt backend/test
```

This will compile the backend and execute the architecture tests along with other backend tests.

Adding or changing rules
-----------------------
- If you need to allow an exception, discuss it in a PR and add a comment in the test explaining the reason.
- To add a rule, edit backend/src/test/scala/com/example/architecture/ArchitectureSpec.scala and include a clear rationale in this document.

CI integration
--------------
Ensure `sbt backend/test` is part of your CI pipeline. Architecture test failures should be treated as build breaks.

Contact
-------
If a rule is too strict or causes friction, open an issue or a PR and tag the backend owners.
