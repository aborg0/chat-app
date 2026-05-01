package com.example.app

import com.example.api.*
import com.example.auth.AuthModule.AuthService
import com.example.chapters.ChaptersModule.ChaptersService
import com.example.groups.GroupsModule.GroupsService
import com.example.messaging.MessagingModule.MessagingService
import com.example.sessions.SessionsModule.SessionsService
import zio.*
import zio.http.*
import zio.json.*

import java.security.SecureRandom

object ApiRoutes {

  private val traceparentHeaderName = "traceparent"
  private val traceparentRegex = "(?i)^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$".r
  private val sessionTokenRegex = "^[A-Za-z0-9_-]{20,200}$".r
  private val maxRequestBodyChars = 100000
  private val random = new SecureRandom()

  type AppEnv = AuthService & SessionsService & MessagingService & ChaptersService & GroupsService

  def routes: Routes[AppEnv, Response] = {
    val baseRoutes = Routes(
      Method.POST / "auth" / "register" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleRegister(req)))
      },
      Method.POST / "auth" / "login" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handlePasswordLogin(req)))
      },
      Method.POST / "auth" / "social-login" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleSocialLogin(req)))
      },
      Method.GET / "sessions" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleListSessions(req)))
      },
      Method.POST / "sessions" / "logout-others" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleLogoutOthers(req)))
      },
      Method.POST / "messages" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleCreateMessage(req)))
      },
      Method.GET / "messages" / "search" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleSearchMessages(req)))
      },
      Method.GET / "messages" / "by-id" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleGetMessageById(req)))
      },
      Method.PUT / "messages" / "by-id" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleEditMessage(req)))
      },
      Method.DELETE / "messages" / "by-id" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleDeleteMessage(req)))
      },
      Method.GET / "messages" / "history" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleMessageHistory(req)))
      },
      Method.POST / "messages" / "share-link" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleCreateMessageShareLink(req)))
      },
      Method.GET / "admin" / "audit" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleAuditEntries(req)))
      },
      Method.GET / "openapi.json" -> handler { (req: Request) =>
        withTraceparent(req)(ZIO.succeed(Response.json(openApiJson)))
      },
      Method.GET / "swagger" -> handler { (req: Request) =>
        withTraceparent(req)(ZIO.succeed(htmlResponse(swaggerUiHtml)))
      },
      // ---- Chapters ----
      Method.POST / "chapters" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleCreateChapter(req)))
      },
      Method.GET / "chapters" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleListChapters(req)))
      },
      Method.GET / "chapters" / "preferences" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleListChapterPreferences(req)))
      },
      Method.GET / "chapters" / long("id") -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleGetChapter(id, req)))
      },
      Method.GET / "chapters" / long("id") / "messages" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleListChapterMessages(id, req)))
      },
      Method.DELETE / "chapters" / long("id") -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleDeleteChapter(id, req)))
      },
      Method.PUT / "chapters" / long("id") / "visibility" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleUpdateChapterVisibility(id, req)))
      },
      Method.POST / "chapters" / long("id") / "members" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleAddChapterMember(id, req)))
      },
      Method.DELETE / "chapters" / long("id") / "members" / long("userId") -> handler { (id: Long, userId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleRemoveChapterMember(id, userId, req)))
      },
      Method.POST / "chapters" / long("id") / "messages" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleAddMessageToChapter(id, req)))
      },
      Method.DELETE / "chapters" / long("id") / "messages" / long("messageId") -> handler { (id: Long, messageId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleRemoveMessageFromChapter(id, messageId, req)))
      },
      Method.POST / "chapters" / long("id") / "messages" / long("messageId") / "read" -> handler { (id: Long, messageId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleMarkMessageRead(id, messageId, req)))
      },
      Method.POST / "chapters" / long("id") / "messages" / long("messageId") / "unread-from" -> handler { (id: Long, messageId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleMarkUnreadFrom(id, messageId, req)))
      },
      Method.POST / "chapters" / long("id") / "share-link" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleCreateChapterShareLink(id, req)))
      },
      Method.GET / "chapters" / long("id") / "preferences" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleGetChapterPreference(id, req)))
      },
      Method.PUT / "chapters" / long("id") / "preferences" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleUpsertChapterPreference(id, req)))
      },
      Method.GET / "chapters" / long("id") / "unread-count" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleChapterUnreadCount(id, req)))
      },
      // ---- Chapter group access ----
      Method.POST / "chapters" / long("id") / "group-access" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleAddChapterGroupAccess(id, req)))
      },
      Method.DELETE / "chapters" / long("id") / "group-access" / long("groupId") -> handler { (id: Long, groupId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleRemoveChapterGroupAccess(id, groupId, req)))
      },
      Method.GET / "chapters" / long("id") / "group-access" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleListChapterGroupAccess(id, req)))
      },
      // ---- Groups ----
      Method.POST / "groups" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleCreateGroup(req)))
      },
      Method.GET / "groups" -> handler { (req: Request) =>
        withTraceparent(req)(asHttpError(handleListGroups(req)))
      },
      Method.DELETE / "groups" / long("id") -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleDeleteGroup(id, req)))
      },
      Method.POST / "groups" / long("id") / "members" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleAddGroupMember(id, req)))
      },
      Method.DELETE / "groups" / long("id") / "members" / long("userId") -> handler { (id: Long, userId: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleRemoveGroupMember(id, userId, req)))
      },
      Method.GET / "groups" / long("id") / "members" -> handler { (id: Long, req: Request) =>
        withTraceparent(req)(asHttpError(handleListGroupMembers(id, req)))
      },
      // ---- Share links (public, no auth required) ----
      Method.GET / "share" / string("token") -> handler { (token: String, req: Request) =>
        withTraceparent(req)(asHttpError(handleResolveShareLink(token)))
      },
      Method.GET / "health" -> handler { (req: Request) =>
        withTraceparent(req)(ZIO.succeed(Response.text("OK")))
      }
    )

    Middleware.cors(baseRoutes)
  }

  private def withTraceparent[R](req: Request)(effect: ZIO[R, Response, Response]): ZIO[R, Response, Response] = {
    val traceparent = resolveTraceparent(req)
    effect
      .map(response => withSecurityHeaders(attachTraceparent(response, traceparent)))
      .mapError(response => withSecurityHeaders(attachTraceparent(response, traceparent)))
  }

  private def resolveTraceparent(req: Request): String = {
    req.headers
      .get(traceparentHeaderName)
      .map(_.trim.toLowerCase)
      .filter(isValidTraceparent)
      .getOrElse(generateTraceparent())
  }

  private def isValidTraceparent(value: String): Boolean = {
    value match {
      case traceparentRegex(traceId, parentId, _) =>
        traceId != "00000000000000000000000000000000" && parentId != "0000000000000000"
      case _ =>
        false
    }
  }

  private def generateTraceparent(): String = {
    val traceId = randomHex(16)
    val parentId = randomHex(8)
    s"00-$traceId-$parentId-01"
  }

  private def randomHex(sizeInBytes: Int): String = {
    val bytes = Array.ofDim[Byte](sizeInBytes)
    random.nextBytes(bytes)
    bytes.map(byte => f"${byte & 0xff}%02x").mkString
  }

  private def attachTraceparent(response: Response, traceparent: String): Response =
    response.addHeaders(Headers(Header.Custom(traceparentHeaderName, traceparent)))

  private def withSecurityHeaders(response: Response): Response = {
    response.addHeaders(
      Headers(
        Header.Custom("x-content-type-options", "nosniff"),
        Header.Custom("x-frame-options", "DENY"),
        Header.Custom("cache-control", "no-store")
      )
    )
  }

  private val openApiJson: String =
    """{
      |  "openapi": "3.0.3",
      |  "info": {
      |    "title": "Chat App Backend API",
      |    "version": "0.1.0"
      |  },
      |  "servers": [
      |    { "url": "/" }
      |  ],
      |  "components": {
      |    "securitySchemes": {
      |      "SessionToken": {
      |        "type": "apiKey",
      |        "in": "header",
      |        "name": "X-Session-Token"
      |      }
      |    }
      |  },
      |  "paths": {
      |    "/health": { "get": { "summary": "Health check" } },
      |    "/auth/register": { "post": { "summary": "Register with username/password" } },
      |    "/auth/login": { "post": { "summary": "Login with username/password" } },
      |    "/auth/social-login": { "post": { "summary": "Login with social identity" } },
      |    "/sessions": { "get": { "summary": "List active sessions", "security": [{"SessionToken": []}] } },
      |    "/sessions/logout-others": { "post": { "summary": "Logout all other sessions", "security": [{"SessionToken": []}] } },
      |    "/messages": { "post": { "summary": "Create message", "security": [{"SessionToken": []}] } },
      |    "/messages/search": { "get": { "summary": "Search messages", "security": [{"SessionToken": []}] } },
      |    "/messages/by-id": {
      |      "get": { "summary": "Get message by id", "security": [{"SessionToken": []}] },
      |      "put": { "summary": "Edit message", "security": [{"SessionToken": []}] },
      |      "delete": { "summary": "Delete message", "security": [{"SessionToken": []}] }
      |    },
      |    "/messages/history": { "get": { "summary": "Message edit history", "security": [{"SessionToken": []}] } },
      |    "/messages/share-link": { "post": { "summary": "Create share link for a message", "security": [{"SessionToken": []}] } },
      |    "/admin/audit": { "get": { "summary": "List audit entries (admin)", "security": [{"SessionToken": []}] } },
      |    "/chapters": {
      |      "get": { "summary": "List accessible chapters", "security": [{"SessionToken": []}] },
      |      "post": { "summary": "Create chapter", "security": [{"SessionToken": []}] }
      |    },
      |    "/chapters/preferences": { "get": { "summary": "List chapter preferences", "security": [{"SessionToken": []}] } },
      |    "/groups": {
      |      "get": { "summary": "List accessible groups", "security": [{"SessionToken": []}] },
      |      "post": { "summary": "Create group", "security": [{"SessionToken": []}] }
      |    },
      |    "/share/{token}": { "get": { "summary": "Resolve share link" } }
      |  }
      |}""".stripMargin

  private val swaggerUiHtml: String =
    """<!doctype html>
      |<html>
      |  <head>
      |    <meta charset="utf-8" />
      |    <meta name="viewport" content="width=device-width, initial-scale=1" />
      |    <title>Chatty API Docs</title>
      |    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css" />
      |    <style>
      |      :root {
      |        --bg: #f5f7fb;
      |        --text: #111827;
      |        --muted: #4b5563;
      |        --surface: #ffffff;
      |        --line: #e5e7eb;
      |        --accent: #0f766e;
      |      }
      |      html, body {
      |        margin: 0;
      |        padding: 0;
      |        background: radial-gradient(circle at top left, #ecfeff, var(--bg) 45%);
      |        color: var(--text);
      |        font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
      |      }
      |      .docs-shell {
      |        max-width: 1240px;
      |        margin: 24px auto;
      |        padding: 0 16px 24px;
      |      }
      |      .docs-hero {
      |        background: linear-gradient(135deg, #0f766e, #115e59);
      |        color: #f8fafc;
      |        border-radius: 14px;
      |        padding: 18px 20px;
      |        margin-bottom: 14px;
      |        box-shadow: 0 10px 24px rgba(15, 118, 110, 0.25);
      |      }
      |      .docs-hero h1 {
      |        margin: 0 0 6px;
      |        font-size: 1.35rem;
      |        letter-spacing: 0.2px;
      |      }
      |      .docs-hero p {
      |        margin: 0;
      |        opacity: 0.9;
      |      }
      |      .docs-frame {
      |        background: var(--surface);
      |        border: 1px solid var(--line);
      |        border-radius: 14px;
      |        overflow: hidden;
      |        box-shadow: 0 8px 18px rgba(15, 23, 42, 0.08);
      |      }
      |      #swagger-ui {
      |        min-height: calc(100vh - 210px);
      |      }
      |      .swagger-ui .topbar {
      |        display: none;
      |      }
      |      .swagger-ui .information-container {
      |        padding: 20px 20px 10px;
      |      }
      |      .swagger-ui .info .title {
      |        color: #0f172a;
      |      }
      |      .swagger-ui .opblock.opblock-get {
      |        border-color: #0ea5e9;
      |      }
      |      .swagger-ui .opblock.opblock-post {
      |        border-color: #10b981;
      |      }
      |      .swagger-ui .opblock.opblock-delete {
      |        border-color: #ef4444;
      |      }
      |      .swagger-ui .btn.execute {
      |        background: var(--accent);
      |        border-color: var(--accent);
      |      }
      |      @media (max-width: 720px) {
      |        .docs-shell {
      |          margin-top: 12px;
      |          padding-left: 10px;
      |          padding-right: 10px;
      |        }
      |        .docs-hero {
      |          border-radius: 10px;
      |        }
      |        .docs-frame {
      |          border-radius: 10px;
      |        }
      |      }
      |    </style>
      |  </head>
      |  <body>
      |    <div class="docs-shell">
      |      <section class="docs-hero">
      |        <h1>Chatty Backend API</h1>
      |        <p>Interactive API explorer for authentication, chapters, groups, messaging, and audit endpoints.</p>
      |      </section>
      |      <section class="docs-frame">
      |        <div id="swagger-ui"></div>
      |      </section>
      |    </div>
      |    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
      |    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
      |    <script>
      |      window.onload = function() {
      |        SwaggerUIBundle({
      |          url: '/openapi.json',
      |          dom_id: '#swagger-ui',
      |          deepLinking: true,
      |          persistAuthorization: true,
      |          displayRequestDuration: true,
      |          tryItOutEnabled: true,
      |          docExpansion: 'list',
      |          presets: [
      |            SwaggerUIBundle.presets.apis,
      |            SwaggerUIStandalonePreset
      |          ],
      |          layout: 'StandaloneLayout'
      |        });
      |      };
      |    </script>
      |  </body>
      |</html>""".stripMargin

  private def htmlResponse(content: String): Response =
    Response(
      status = Status.Ok,
      headers = Headers(Header.Custom("content-type", "text/html; charset=utf-8")),
      body = Body.fromString(content)
    )

  private def asHttpError[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Response, Response] = {
    effect.mapError { error =>
      val message = Option(error.getMessage).getOrElse("Request failed")
      if message.contains("Missing header 'X-Session-Token'") || message.contains("Invalid or inactive session token") then {
        Response.unauthorized(message)
      } else if message.contains("Invalid credentials") || message.contains("Re-authentication failed") then {
        Response.unauthorized("Invalid credentials")
      } else if message.contains("Admin rights are required") || message.contains("Not allowed") then {
        Response.forbidden(message)
      } else if message.toLowerCase.contains("optimistic concurrency conflict") then {
        Response(status = Status.Conflict, body = Body.fromString(message))
      } else if message.contains("Unsupported social login provider") then {
        Response(status = Status.BadRequest, body = Body.fromString(message))
      } else if isSafeClientMessage(message) then {
        Response.badRequest(message)
      } else {
        Response(status = Status.InternalServerError, body = Body.fromString("Request failed"))
      }
    }
  }

  private def isSafeClientMessage(message: String): Boolean = {
    val lower = message.toLowerCase
    lower.contains("missing") ||
    lower.contains("invalid") ||
    lower.contains("must be") ||
    lower.contains("cannot") ||
    lower.contains("not found") ||
    lower.contains("already exists")
  }

  private def handleRegister(req: Request): ZIO[AuthService, Throwable, Response] = {
    for {
      payload <- decodeBody[RegisterRequest](req)
      userId  <- ZIO.serviceWithZIO[AuthService](_.registerPasswordUser(payload.username, payload.password))
    } yield Response.json(RegisterResponse(userId).toJson)
  }

  private def handlePasswordLogin(req: Request): ZIO[AuthService, Throwable, Response] = {
    for {
      payload <- decodeBody[LoginRequest](req)
      auth    <- ZIO.serviceWithZIO[AuthService](_.loginPassword(payload.username, payload.password, payload.deviceId))
    } yield Response.json(AuthResponse(auth.userId, auth.sessionToken).toJson)
  }

  private def handleSocialLogin(req: Request): ZIO[AuthService, Throwable, Response] = {
    for {
      payload <- decodeBody[SocialLoginRequest](req)
      auth    <- ZIO.serviceWithZIO[AuthService](_.socialLogin(payload.provider, payload.providerUserId, payload.displayName, payload.deviceId))
    } yield Response.json(AuthResponse(auth.userId, auth.sessionToken).toJson)
  }

  private def handleListSessions(req: Request): ZIO[SessionsService, Throwable, Response] = {
    for {
      userId  <- readAuthenticatedUserId(req)
      cursor   = optionalStringQuery(req, "cursor")
      pageSize = optionalIntQuery(req, "pageSize").getOrElse(25)
      page    <- ZIO.serviceWithZIO[SessionsService](_.getActiveSessions(userId, cursor, pageSize))
      payload  = ActiveSessionsPage(
        items = page.items.map { session =>
          ActiveSession(
            sessionToken = session.sessionToken,
            deviceId = session.deviceId,
            createdAtEpochMillis = session.createdAt.toEpochMilli
          )
        },
        nextCursor = page.nextCursor
      )
    } yield Response.json(payload.toJson)
  }

  private def handleLogoutOthers(req: Request): ZIO[AuthService & SessionsService, Throwable, Response] = {
    for {
      currentSessionToken <- readSessionToken(req)
      userId              <- ZIO.serviceWithZIO[SessionsService](_.requireActiveUser(currentSessionToken))
      payload             <- decodeBody[LogoutOthersRequest](req)
      password            <- ZIO.fromOption(payload.password).orElseFail(new RuntimeException("Password re-authentication is required"))
      _                   <- ZIO.serviceWithZIO[AuthService](_.reauthenticate(userId, password))
      _                   <- ZIO.serviceWithZIO[SessionsService](_.logoutFromOtherDevices(userId, currentSessionToken))
    } yield Response.ok
  }

  private def handleCreateMessage(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      payload         <- decodeBody[CreateMessageRequest](req)
      message         <- ZIO.serviceWithZIO[MessagingService](
        _.createMessage(requesterUserId, payload.content, payload.clientEditedAtEpochMillis)
      )
    } yield Response.json(toMessageResponse(message).toJson)
  }

  private def handleSearchMessages(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      query           <- readStringQuery(req, "q")
      targetUserId     = optionalLongQuery(req, "targetUserId")
      cursor           = optionalStringQuery(req, "cursor")
      pageSize         = optionalIntQuery(req, "pageSize").getOrElse(25)
      page            <- ZIO.serviceWithZIO[MessagingService](_.searchMessages(requesterUserId, query, targetUserId, cursor, pageSize))
      payload          = MessageSearchPage(items = page.items.map(toMessageResponse), nextCursor = page.nextCursor)
    } yield Response.json(payload.toJson)
  }

  private def handleGetMessageById(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      messageId       <- readLongQuery(req, "messageId")
      message         <- ZIO.serviceWithZIO[MessagingService](_.getMessageById(requesterUserId, messageId))
    } yield Response.json(toMessageResponse(message).toJson)
  }

  private def handleEditMessage(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      messageId       <- readLongQuery(req, "messageId")
      payload         <- decodeBody[EditMessageRequest](req)
      updated         <- ZIO.serviceWithZIO[MessagingService](
        _.editMessage(
          requesterUserId,
          messageId,
          payload.content,
          payload.expectedVersion,
          payload.clientEditedAtEpochMillis
        )
      )
    } yield Response.json(toMessageResponse(updated).toJson)
  }

  private def handleDeleteMessage(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      actorUserId <- readAuthenticatedUserId(req)
      messageId   <- readLongQuery(req, "messageId")
      _           <- ZIO.serviceWithZIO[MessagingService](_.deleteMessage(actorUserId, messageId))
    } yield Response.ok
  }

  private def handleMessageHistory(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      messageId       <- readLongQuery(req, "messageId")
      entries         <- ZIO.serviceWithZIO[MessagingService](_.messageHistory(requesterUserId, messageId))
      payload          = entries.map { entry =>
        MessageHistoryEntry(
          version = entry.version,
          previousContent = entry.previousContent,
          newContent = entry.newContent,
          editedByUserId = entry.editedByUserId,
          editedAtEpochMillis = entry.editedAt.toEpochMilli
        )
      }
    } yield Response.json(payload.toJson)
  }

  private def handleAuditEntries(req: Request): ZIO[SessionsService & MessagingService, Throwable, Response] = {
    for {
      requesterUserId <- readAuthenticatedUserId(req)
      targetUserId     = optionalLongQuery(req, "targetUserId")
      messageId        = optionalLongQuery(req, "messageId")
      cursor           = optionalStringQuery(req, "cursor")
      pageSize         = optionalIntQuery(req, "pageSize").getOrElse(25)
      page            <- ZIO.serviceWithZIO[MessagingService](_.listAuditEntries(requesterUserId, targetUserId, messageId, cursor, pageSize))
      payload          = AuditEntriesPage(
        items = page.items.map { entry =>
          AuditEntryResponse(
            id = entry.id,
            actorUserId = entry.actorUserId,
            action = entry.action,
            targetUserId = entry.targetUserId,
            messageId = entry.messageId,
            details = entry.details,
            createdAtEpochMillis = entry.createdAt.toEpochMilli
          )
        },
        nextCursor = page.nextCursor
      )
    } yield Response.json(payload.toJson)
  }

  private def toMessageResponse(message: com.example.messaging.MessagingModule.MessageData): MessageResponse =
    MessageResponse(
      id = message.id,
      authorUserId = message.authorUserId,
      content = message.content,
      deleted = message.deleted,
      version = message.version,
      createdAtEpochMillis = message.createdAt.toEpochMilli,
      updatedAtEpochMillis = message.updatedAt.toEpochMilli,
      clientEditedAtEpochMillis = message.clientEditedAt.map(_.toEpochMilli),
      deepLink = s"/messages/by-id?messageId=${message.id}"
    )

  private def decodeBody[A: JsonDecoder](req: Request): Task[A] =
    for {
      body   <- req.body.asString
      _      <- ZIO.fail(new RuntimeException("Request body too large")).when(body.length > maxRequestBodyChars)
      parsed <- ZIO.fromEither(body.fromJson[A]).mapError(err => new RuntimeException(err))
    } yield parsed

  private def readLongQuery(req: Request, key: String): Task[Long] =
    readStringQuery(req, key).flatMap { value =>
      ZIO.attempt(value.toLong).mapError(_ => new RuntimeException(s"Invalid query parameter '$key'"))
    }

  private def optionalLongQuery(req: Request, key: String): Option[Long] =
    req.queryParam(key).flatMap(value => scala.util.Try(value.toLong).toOption)

  private def optionalIntQuery(req: Request, key: String): Option[Int] =
    req.queryParam(key).flatMap(value => scala.util.Try(value.toInt).toOption)

  private def optionalStringQuery(req: Request, key: String): Option[String] =
    req.queryParam(key).map(_.trim).filter(_.nonEmpty)

  private def readStringQuery(req: Request, key: String): Task[String] =
    ZIO.fromOption(req.queryParam(key)).orElseFail(new RuntimeException(s"Missing query parameter '$key'"))

  private def readAuthenticatedUserId(req: Request): ZIO[SessionsService, Throwable, Long] =
    readSessionToken(req).flatMap { token =>
      ZIO.serviceWithZIO[SessionsService](_.requireActiveUser(token))
    }

  private def readSessionToken(req: Request): Task[String] = {
    val headerName = "X-Session-Token"
    ZIO.fromOption(req.headers.get(headerName))
      .orElseFail(new RuntimeException(s"Missing header '$headerName'"))
      .map(_.trim)
      .filterOrFail(token => sessionTokenRegex.matches(token))(new RuntimeException("Invalid or inactive session token"))
  }

  // ---- Chapter handlers ----

  private def toChapterResponse(c: com.example.chapters.ChaptersModule.Chapter): ChapterResponse =
    ChapterResponse(c.id, c.ownerUserId, c.title, c.parentChapterId, c.visibility, c.createdAtEpochMillis)

  private def toChapterPreferenceResponse(pref: com.example.chapters.ChaptersModule.ChapterPreference): ChapterPreferenceResponse =
    ChapterPreferenceResponse(
      chapterId = pref.chapterId,
      isImportant = pref.isImportant,
      muteLevel = pref.muteLevel,
      updatedAtEpochMillis = pref.updatedAtEpochMillis
    )

  private def handleCreateChapter(req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[CreateChapterRequest](req)
      chapter <- ZIO.serviceWithZIO[ChaptersService](_.createChapter(userId, payload.title, payload.parentChapterId))
    } yield Response.json(toChapterResponse(chapter).toJson)

  private def handleListChapters(req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId   <- readAuthenticatedUserId(req)
      chapters <- ZIO.serviceWithZIO[ChaptersService](_.listAccessibleChapters(userId))
    } yield Response.json(chapters.map(toChapterResponse).toJson)

  private def handleListChapterPreferences(req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      prefs  <- ZIO.serviceWithZIO[ChaptersService](_.listPreferences(userId))
    } yield Response.json(prefs.map(toChapterPreferenceResponse).toJson)

  private def handleGetChapter(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      detail <- ZIO.serviceWithZIO[ChaptersService](_.getChapterDetail(userId, chapterId))
      payload = ChapterDetailResponse(
        chapter = toChapterResponse(detail.chapter),
        members = detail.members.map(m => ChapterMemberResponse(m.userId, m.role)),
        messageIds = detail.messageIds
      )
    } yield Response.json(payload.toJson)

  private def handleListChapterMessages(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId   <- readAuthenticatedUserId(req)
      cursor    = optionalStringQuery(req, "cursor")
      pageSize  = optionalIntQuery(req, "pageSize").getOrElse(25)
      page     <- ZIO.serviceWithZIO[ChaptersService](_.listChapterMessages(userId, chapterId, cursor, pageSize))
      payload   = MessageSearchPage(items = page.items.map(toMessageResponse), nextCursor = page.nextCursor)
    } yield Response.json(payload.toJson)

  private def handleDeleteChapter(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[ChaptersService](_.deleteChapter(userId, chapterId))
    } yield Response.ok

  private def handleUpdateChapterVisibility(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[UpdateChapterVisibilityRequest](req)
      _       <- ZIO.serviceWithZIO[ChaptersService](_.updateVisibility(userId, chapterId, payload.visibility))
    } yield Response.ok

  private def handleAddChapterMember(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[AddChapterMemberRequest](req)
      _       <- ZIO.serviceWithZIO[ChaptersService](_.addMember(userId, chapterId, payload.userId, payload.role))
    } yield Response.ok

  private def handleRemoveChapterMember(chapterId: Long, targetUserId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[ChaptersService](_.removeMember(userId, chapterId, targetUserId))
    } yield Response.ok

  private def handleAddMessageToChapter(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[AddMessageToChapterRequest](req)
      _       <- ZIO.serviceWithZIO[ChaptersService](_.addMessageToChapter(userId, chapterId, payload.messageId))
    } yield Response.ok

  private def handleRemoveMessageFromChapter(chapterId: Long, messageId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[ChaptersService](_.removeMessageFromChapter(userId, chapterId, messageId))
    } yield Response.ok

  private def handleMarkMessageRead(chapterId: Long, messageId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[ChaptersService](_.markMessageRead(userId, chapterId, messageId))
    } yield Response.ok

  private def handleMarkUnreadFrom(chapterId: Long, messageId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[ChaptersService](_.markUnreadFrom(userId, chapterId, messageId))
    } yield Response.ok

  private def handleGetChapterPreference(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      pref   <- ZIO.serviceWithZIO[ChaptersService](_.getPreference(userId, chapterId))
    } yield Response.json(toChapterPreferenceResponse(pref).toJson)

  private def handleUpsertChapterPreference(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[UpdateChapterPreferenceRequest](req)
      pref    <- ZIO.serviceWithZIO[ChaptersService](_.upsertPreference(userId, chapterId, payload.isImportant, payload.muteLevel))
    } yield Response.json(toChapterPreferenceResponse(pref).toJson)

  private def handleChapterUnreadCount(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      state  <- ZIO.serviceWithZIO[ChaptersService](_.unreadCount(userId, chapterId))
    } yield Response.json(ChapterUnreadCountResponse(state.chapterId, state.unreadCount, state.muteLevel).toJson)

  private def handleCreateChapterShareLink(chapterId: Long, req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      token  <- ZIO.serviceWithZIO[ChaptersService](_.createShareLink(userId, Some(chapterId), None))
    } yield Response.json(ShareLinkResponse(token).toJson)

  private def handleCreateMessageShareLink(req: Request): ZIO[SessionsService & ChaptersService, Throwable, Response] =
    for {
      userId    <- readAuthenticatedUserId(req)
      messageId <- readLongQuery(req, "messageId")
      token     <- ZIO.serviceWithZIO[ChaptersService](_.createShareLink(userId, None, Some(messageId)))
    } yield Response.json(ShareLinkResponse(token).toJson)

  private def handleResolveShareLink(token: String): ZIO[ChaptersService, Throwable, Response] =
    for {
      target <- ZIO.serviceWithZIO[ChaptersService](_.resolveShareLink(token))
      payload = ShareLinkTargetResponse(target.targetType, target.chapterId, target.messageId)
    } yield Response.json(payload.toJson)

  // ---- Group handlers ----

  private def toGroupResponse(g: com.example.groups.GroupsModule.Group): GroupResponse =
    GroupResponse(g.id, g.ownerUserId, g.name, g.createdAtEpochMillis)

  private def handleCreateGroup(req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[CreateGroupRequest](req)
      group   <- ZIO.serviceWithZIO[GroupsService](_.createGroup(userId, payload.name))
    } yield Response.json(toGroupResponse(group).toJson)

  private def handleListGroups(req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      groups <- ZIO.serviceWithZIO[GroupsService](_.listAccessibleGroups(userId))
    } yield Response.json(groups.map(toGroupResponse).toJson)

  private def handleDeleteGroup(groupId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[GroupsService](_.deleteGroup(userId, groupId))
    } yield Response.ok

  private def handleAddGroupMember(groupId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[GroupMemberRequest](req)
      _       <- ZIO.serviceWithZIO[GroupsService](_.addMember(userId, groupId, payload.userId))
    } yield Response.ok

  private def handleRemoveGroupMember(groupId: Long, targetUserId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[GroupsService](_.removeMember(userId, groupId, targetUserId))
    } yield Response.ok

  private def handleListGroupMembers(groupId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      members <- ZIO.serviceWithZIO[GroupsService](_.listMembers(userId, groupId))
    } yield Response.json(GroupMembersResponse(members).toJson)

  private def handleAddChapterGroupAccess(chapterId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId  <- readAuthenticatedUserId(req)
      payload <- decodeBody[ChapterGroupAccessRequest](req)
      _       <- ZIO.serviceWithZIO[GroupsService](_.addChapterGroupAccess(userId, chapterId, payload.groupId))
    } yield Response.ok

  private def handleRemoveChapterGroupAccess(chapterId: Long, groupId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId <- readAuthenticatedUserId(req)
      _      <- ZIO.serviceWithZIO[GroupsService](_.removeChapterGroupAccess(userId, chapterId, groupId))
    } yield Response.ok

  private def handleListChapterGroupAccess(chapterId: Long, req: Request): ZIO[SessionsService & GroupsService, Throwable, Response] =
    for {
      userId   <- readAuthenticatedUserId(req)
      groupIds <- ZIO.serviceWithZIO[GroupsService](_.listChapterGroupAccess(userId, chapterId))
    } yield Response.json(ChapterGroupAccessResponse(groupIds).toJson)
}
