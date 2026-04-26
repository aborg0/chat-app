---
description: "Use when adding or modifying shared Scala types: Protocol DTOs, API request/response models, or cross-platform data types used by both backend and frontend. Covers zio-json codec conventions and DTO grouping."
applyTo: "shared/src/**/*.scala"
---

# Shared Module Conventions

The `shared/` module compiles for both JVM (backend) and Scala.js (frontend). Keep it free of platform-specific dependencies — only `zio-json` and the Scala standard library are allowed.

## DTO Structure

Every DTO is a `final case class` with a companion object containing both `JsonEncoder` and `JsonDecoder` givens derived with `DeriveJsonEncoder.gen` / `DeriveJsonDecoder.gen`:

```scala
final case class CreateGroupRequest(name: String)
object CreateGroupRequest {
  given JsonEncoder[CreateGroupRequest] = DeriveJsonEncoder.gen[CreateGroupRequest]
  given JsonDecoder[CreateGroupRequest] = DeriveJsonDecoder.gen[CreateGroupRequest]
}
```

- Always derive **both** encoder and decoder, even if only one direction is currently used.
- Use `Long` for IDs and epoch-millisecond timestamps (`createdAtEpochMillis: Long`), never `java.time` types.
- Use `Option[T]` for nullable fields; never use `null`.

## Grouping in Protocol.scala

All types live in `Protocol.scala`, grouped under comment headers that match the backend module they relate to:

```scala
// ---- Auth ----
// ---- Sessions ----
// ---- Messaging ----
// ---- Chapters ----
// ---- Groups ----
```

Add new types at the end of the relevant group, not scattered through the file.

## Naming Conventions

| Category | Pattern | Example |
|----------|---------|---------|
| Request body | `{Action}{Entity}Request` | `CreateChapterRequest` |
| Response body | `{Entity}Response` | `ChapterResponse` |
| Paginated response | `{Entity}Page` | `MessageSearchPage` |
| Sub-object in response | `{Entity}{Role}Response` | `ChapterMemberResponse` |

## Scala 3 Syntax

Use `given` (not `implicit`), `DeriveJsonEncoder.gen` (not `genEncoder`). No explicit type ascription on `given` definitions unless the compiler requires it.
