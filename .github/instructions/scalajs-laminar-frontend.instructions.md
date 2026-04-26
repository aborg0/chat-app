---
description: "Use when writing frontend Scala.js code: Laminar reactive UI, BackendClient API calls, Var/Signal state management, or adding new views. Covers reactive patterns, Future handling, and BackendClient conventions for this project."
applyTo: "frontend/src/**/*.scala"
---

# Frontend Scala.js / Laminar Conventions

## State Management with Var

Use `Var` for mutable reactive state. Use `.signal` for read-only derived signals:

```scala
val itemsVar  = Var(List.empty[ItemResponse])
val errorVar  = Var("")
val inputVar  = Var("")

// Render reactively
child <-- errorVar.signal.map { err =>
  if err.nonEmpty then p(color := "red", err) else span()
}
```

## Controlled Inputs

Always use `controlled()` for text inputs to avoid React-style uncontrolled warnings:

```scala
input(
  typ := "text",
  placeholder := "Group name",
  controlled(
    value <-- inputVar.signal,
    onInput.mapToValue --> inputVar.writer
  )
)
```

## Calling Backend APIs

Use `BackendClient` methods which return `Future[Either[String, A]]`. Handle inline with `.foreach`:

```scala
BackendClient.listGroups(auth.sessionToken).foreach {
  case Right(groups) =>
    groupsVar.set(groups)
    errorVar.set("")
  case Left(err) =>
    errorVar.set(s"Error: $err")
}
```

- Never block on Futures; always use `.foreach` (fire-and-forget) inside event handlers.
- Always update an `errorVar` on failure — don't silently discard errors.
- The implicit `ExecutionContext` in `BackendClient` is `JSExecutionContext.queue`; frontend views need to import `scala.scalajs.concurrent.JSExecutionContext.Implicits.queue` if they use Futures directly.

## Adding BackendClient Methods

New methods follow the established pattern: choose the right private helper based on HTTP verb and whether a response body is expected:

```scala
// POST with request + response body
def createGroup(token: String, name: String): Future[Either[String, GroupResponse]] =
  postJson[CreateGroupRequest, GroupResponse]("/groups", CreateGroupRequest(name), Map("X-Session-Token" -> token))

// DELETE, no response body
def deleteGroup(token: String, id: Long): Future[Either[String, Unit]] =
  deleteNoResponse(s"/groups/$id", Map("X-Session-Token" -> token))

// GET with response body
def listGroups(token: String): Future[Either[String, List[GroupResponse]]] =
  getJson[List[GroupResponse]]("/groups", Map("X-Session-Token" -> token))
```

All helpers: `postJson`, `postNoResponse`, `postEmptyJson`, `putJson`, `putNoResponse`, `deleteNoResponse`, `getJson`.

## View Structure

Each view is a Scala object with a `def view(auth: AuthResponse): HtmlElement` entry point. Initialize state, kick off data loading, then return the root element:

```scala
object GroupsView {
  def view(auth: AuthResponse): HtmlElement = {
    val itemsVar = Var(List.empty[...])
    def loadItems(): Unit = BackendClient.listX(auth.sessionToken).foreach { ... }
    loadItems()
    div(
      h3("Title"),
      child <-- itemsVar.signal.map { ... }
    )
  }
}
```

- Keep loading side effects in local `def` functions inside `view`, not at the object level.
- Use `emptyNode` (not `span()`) for conditional no-op children.

## Protocol DTOs (shared module)

All request/response types live in `shared/src/main/scala/com/example/api/Protocol.scala`. Every case class needs both `JsonEncoder` and `JsonDecoder` givens derived via `DeriveJsonEncoder.gen` / `DeriveJsonDecoder.gen`. Group related types under a comment header.
