package com.example.integration

import com.example.api.*
import com.example.auth.AuthModule
import com.example.app.ApiRoutes
import com.example.chapters.ChaptersModule
import com.example.groups.GroupsModule
import com.example.infrastructure.db.{Database, JdbcDatabase, Migrations, SkunkSessionPool}
import com.example.messaging.MessagingModule
import com.example.messaging.DraftsModule
import com.example.messaging.TypingModule
import com.example.sessions.SessionsModule
import org.testcontainers.containers.PostgreSQLContainer
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

object MessagingIntegrationSpec extends ZIOSpecDefault {
  final case class Fixture(
    db: JdbcDatabase,
    routes: Routes[Any, Response]
  )

  private def fixtureLayer(useSkunk: Boolean): ZLayer[Any, Throwable, Fixture] = ZLayer.scoped {
    for {
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking { val c = new PostgreSQLContainer("postgres:18.3-alpine"); c.start(); c }
      )(c => ZIO.succeed(c.stop()))
      _ <- Migrations.migrate(container.getJdbcUrl, container.getUsername, container.getPassword)
      db         = new JdbcDatabase(container.getJdbcUrl, container.getUsername, container.getPassword)
      dbLayer    = ZLayer.succeed[Database](db)
      skunkLayer =
        if useSkunk then
          SkunkSessionPool.layer(container.getJdbcUrl, container.getUsername, container.getPassword, maxSessions = 4)
        else
          SkunkSessionPool.disabled
      dbWithSkunk = dbLayer ++ skunkLayer
      sessLayer   = dbWithSkunk >>> SessionsModule.layer
      authLayer   = (dbLayer ++ sessLayer) >>> AuthModule.layer
      msgLayer    = dbWithSkunk >>> MessagingModule.layer
      chapLayer   = dbWithSkunk >>> ChaptersModule.layer
      grpLayer    = dbWithSkunk >>> GroupsModule.layer
      allLayers   = sessLayer ++ authLayer ++ msgLayer ++ chapLayer ++ grpLayer ++ DraftsModule.live ++ (dbLayer >>> TypingModule.live)
      env        <- allLayers.build
    } yield Fixture(db, ApiRoutes.routes.provideEnvironment(env))
  }

  private val jdbcFixtureLayer  = fixtureLayer(useSkunk = false)
  private val skunkFixtureLayer = fixtureLayer(useSkunk = true)

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("MessagingIntegrationSpec")(
      suite("jdbc-fallback")(
      test("message search + deep-link + edit/history + delete over HTTP") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              register <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("msg-user", "Secret123!").toJson)
              login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("msg-user", "Secret123!", "msg-device-1").toJson)
              create <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("Hello world from user").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              search <- getJson[MessageSearchPage](
                port,
                s"/messages/search?q=Hello&pageSize=10",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              byId <- getJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${create.id}",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- putJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${create.id}",
                EditMessageRequest("Hello world edited").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              history <- getJson[List[MessageHistoryEntry]](
                port,
                s"/messages/history?messageId=${create.id}",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- deleteNoBody(
                port,
                s"/messages/by-id?messageId=${create.id}",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              afterDelete <- getJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${create.id}",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected one search result")).unless(search.items.size == 1)
              _ <- ZIO.fail(new RuntimeException("Deep link should contain message id")).unless(byId.deepLink.contains(s"messageId=${create.id}"))
              _ <- ZIO.fail(new RuntimeException("Expected one history entry after one edit")).unless(history.size == 1)
              _ <- ZIO.fail(new RuntimeException("Message should be marked deleted")).unless(afterDelete.deleted)
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("admin reads and writes are audited for other users") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              user <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("alice-admin-target", "Secret123!").toJson)
              admin <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("admin-user", "Secret123!").toJson)
              _ <- makeAdmin(fixture.db, admin.userId)
              userLogin <- postJson[AuthResponse](port, "/auth/login", LoginRequest("alice-admin-target", "Secret123!", "target-device").toJson)
              adminLogin <- postJson[AuthResponse](port, "/auth/login", LoginRequest("admin-user", "Secret123!", "admin-device").toJson)
              created <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("Admin should audit this message").toJson,
                headers = Map("X-Session-Token" -> userLogin.sessionToken)
              )
              _ <- getJson[MessageSearchPage](
                port,
                s"/messages/search?targetUserId=${user.userId}&q=audit&pageSize=10",
                headers = Map("X-Session-Token" -> adminLogin.sessionToken)
              )
              _ <- getJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${created.id}",
                headers = Map("X-Session-Token" -> adminLogin.sessionToken)
              )
              _ <- getJson[List[MessageHistoryEntry]](
                port,
                s"/messages/history?messageId=${created.id}",
                headers = Map("X-Session-Token" -> adminLogin.sessionToken)
              )
              _ <- putJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${created.id}",
                EditMessageRequest("Admin edited content").toJson,
                headers = Map("X-Session-Token" -> adminLogin.sessionToken)
              )
              audits <- getJson[AuditEntriesPage](
                port,
                s"/admin/audit?targetUserId=${user.userId}&pageSize=10",
                headers = Map("X-Session-Token" -> adminLogin.sessionToken)
              )
              actions = audits.items.map(_.action).toSet
              _ <- ZIO.fail(new RuntimeException("Expected admin read message audit")).unless(actions.contains("admin.read.message"))
              _ <- ZIO.fail(new RuntimeException("Expected admin read message history audit")).unless(actions.contains("admin.read.message_history"))
              _ <- ZIO.fail(new RuntimeException("Expected admin search audit")).unless(actions.contains("admin.search.messages"))
              _ <- ZIO.fail(new RuntimeException("Expected admin write edit audit")).unless(actions.contains("admin.write.edit_message"))
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("message edit uses optimistic concurrency and stores client edit timestamp") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("version-user", "Secret123!").toJson)
              login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("version-user", "Secret123!", "version-device").toJson)
              clientEditedAt = 1777000000000L
              created <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("versioned", Some(clientEditedAt)).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected created version to be 1")).unless(created.version == 1)
              _ <- ZIO.fail(new RuntimeException("Expected client edited timestamp on created message")).unless(
                created.clientEditedAtEpochMillis.contains(clientEditedAt)
              )

              updated <- putJson[MessageResponse](
                port,
                s"/messages/by-id?messageId=${created.id}",
                EditMessageRequest("versioned-2", expectedVersion = Some(created.version), clientEditedAtEpochMillis = Some(clientEditedAt + 1000)).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected updated version to be incremented")).unless(updated.version == 2)

              staleEdit <- send(
                port,
                "PUT",
                s"/messages/by-id?messageId=${created.id}",
                Some(EditMessageRequest("stale-write", expectedVersion = Some(1)).toJson),
                headers = Map("X-Session-Token" -> login.sessionToken)
              ).either
              _ <- staleEdit match {
                case Left(error) if error.getMessage.contains("HTTP 409") => ZIO.unit
                case Left(error) => ZIO.fail(new RuntimeException(s"Expected HTTP 409 conflict, got: ${error.getMessage}"))
                case Right(_) => ZIO.fail(new RuntimeException("Expected conflict response for stale version"))
              }
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("messaging endpoints require session token") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("no-token-user", "Secret123!").toJson)
              response <- send(port, "GET", "/messages/search?q=hello", None).either
              _ <- response match {
                case Left(err) if err.getMessage.contains("HTTP 401") => ZIO.unit
                case Left(err) => ZIO.fail(new RuntimeException(s"Expected HTTP 401, got: ${err.getMessage}"))
                case Right(_) => ZIO.fail(new RuntimeException("Expected request failure without token"))
              }
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("backend returns traceparent header and propagates client value") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              generated <- sendWithHeaders(port, "GET", "/health", None)
              generatedTraceparent <- ZIO
                .fromOption(generated._2.get("traceparent"))
                .orElseFail(new RuntimeException("Expected traceparent header to be present"))
              _ <- ZIO.fail(new RuntimeException("Generated traceparent has invalid format")).unless(
                generatedTraceparent.matches("(?i)^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
              )
              clientTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
              echoed <- sendWithHeaders(
                port,
                "GET",
                "/health",
                None,
                headers = Map("traceparent" -> clientTraceparent)
              )
              echoedTraceparent <- ZIO
                .fromOption(echoed._2.get("traceparent"))
                .orElseFail(new RuntimeException("Expected echoed traceparent header"))
              _ <- ZIO.fail(new RuntimeException("Expected backend to propagate client traceparent")).unless(
                echoedTraceparent == clientTraceparent
              )
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("swagger and openapi endpoints are available") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              openApi <- sendWithHeaders(port, "GET", "/openapi.json", None)
              swagger <- sendWithHeaders(port, "GET", "/swagger", None)
              _ <- ZIO.fail(new RuntimeException("Expected openapi document response")).unless(openApi._1.contains("\"openapi\""))
              _ <- ZIO.fail(new RuntimeException("Expected swagger ui html response")).unless(swagger._1.contains("SwaggerUIBundle"))
              _ <- ZIO.fail(new RuntimeException("Expected traceparent on openapi response")).unless(openApi._2.contains("traceparent"))
              _ <- ZIO.fail(new RuntimeException("Expected traceparent on swagger response")).unless(swagger._2.contains("traceparent"))
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("non-admin cannot search another user's messages") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              userA <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("search-user-a", "Secret123!").toJson)
              userB <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("search-user-b", "Secret123!").toJson)
              loginA <- postJson[AuthResponse](port, "/auth/login", LoginRequest("search-user-a", "Secret123!", "search-device-a").toJson)
              loginB <- postJson[AuthResponse](port, "/auth/login", LoginRequest("search-user-b", "Secret123!", "search-device-b").toJson)
              _ <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("private message from user a").toJson,
                headers = Map("X-Session-Token" -> loginA.sessionToken)
              )
              response <- send(
                port,
                "GET",
                s"/messages/search?targetUserId=${userA.userId}&q=private",
                None,
                headers = Map("X-Session-Token" -> loginB.sessionToken)
              ).either
              _ <- response match {
                case Left(err) if err.getMessage.contains("HTTP 403") => ZIO.unit
                case Left(err) => ZIO.fail(new RuntimeException(s"Expected HTTP 403, got: ${err.getMessage}"))
                case Right(_) => ZIO.fail(new RuntimeException("Expected non-admin cross-user search to fail"))
              }
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("chapter preferences and read-state endpoints work over HTTP") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("chapter-pref-user", "Secret123!").toJson)
              login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("chapter-pref-user", "Secret123!", "chapter-pref-device").toJson)
              chapter <- postJson[ChapterResponse](
                port,
                "/chapters",
                CreateChapterRequest("Important Chapter", None).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              firstMessage <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("chapter message 1").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              secondMessage <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("chapter message 2").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages",
                AddMessageToChapterRequest(firstMessage.id).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages",
                AddMessageToChapterRequest(secondMessage.id).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              initialUnread <- getJson[ChapterUnreadCountResponse](
                port,
                s"/chapters/${chapter.id}/unread-count",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages/${firstMessage.id}/read",
                "",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              afterRead <- getJson[ChapterUnreadCountResponse](
                port,
                s"/chapters/${chapter.id}/unread-count",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              updatedPreference <- putJson[ChapterPreferenceResponse](
                port,
                s"/chapters/${chapter.id}/preferences",
                UpdateChapterPreferenceRequest(isImportant = true, muteLevel = "soft").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              fetchedPreference <- getJson[ChapterPreferenceResponse](
                port,
                s"/chapters/${chapter.id}/preferences",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              listedPreferences <- getJson[List[ChapterPreferenceResponse]](
                port,
                "/chapters/preferences",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages/${firstMessage.id}/unread-from",
                "",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              unreadAfterUnreadFrom <- getJson[ChapterUnreadCountResponse](
                port,
                s"/chapters/${chapter.id}/unread-count",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- putJson[ChapterPreferenceResponse](
                port,
                s"/chapters/${chapter.id}/preferences",
                UpdateChapterPreferenceRequest(isImportant = true, muteLevel = "hard").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              unreadWhenHardMuted <- getJson[ChapterUnreadCountResponse](
                port,
                s"/chapters/${chapter.id}/unread-count",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected 2 unread messages initially")).unless(initialUnread.unreadCount == 2)
              _ <- ZIO.fail(new RuntimeException("Expected 1 unread message after marking one as read")).unless(afterRead.unreadCount == 1)
              _ <- ZIO.fail(new RuntimeException("Expected soft mute preference update")).unless(updatedPreference.muteLevel == "soft" && updatedPreference.isImportant)
              _ <- ZIO.fail(new RuntimeException("Fetched preference did not match updated value")).unless(fetchedPreference.muteLevel == "soft" && fetchedPreference.isImportant)
              _ <- ZIO.fail(new RuntimeException("Expected preferences list to include chapter preference")).unless(listedPreferences.exists(_.chapterId == chapter.id))
              _ <- ZIO.fail(new RuntimeException("Expected unread-from to mark all chapter messages unread")).unless(unreadAfterUnreadFrom.unreadCount == 2)
              _ <- ZIO.fail(new RuntimeException("Expected hard mute to suppress unread badge count")).unless(unreadWhenHardMuted.unreadCount == 0 && unreadWhenHardMuted.muteLevel == "hard")
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("chapter timeline endpoint returns chapter messages with cursor pagination") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("chapter-timeline-user", "Secret123!").toJson)
              login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("chapter-timeline-user", "Secret123!", "chapter-timeline-device").toJson)
              chapter <- postJson[ChapterResponse](
                port,
                "/chapters",
                CreateChapterRequest("Timeline Chapter", None).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              firstMessage <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("timeline message 1").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              secondMessage <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("timeline message 2").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages",
                AddMessageToChapterRequest(firstMessage.id).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postNoBody(
                port,
                s"/chapters/${chapter.id}/messages",
                AddMessageToChapterRequest(secondMessage.id).toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              firstPage <- getJson[MessageSearchPage](
                port,
                s"/chapters/${chapter.id}/messages?pageSize=1",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              nextCursor <- ZIO
                .fromOption(firstPage.nextCursor)
                .orElseFail(new RuntimeException("Expected nextCursor on first chapter timeline page"))
              secondPage <- getJson[MessageSearchPage](
                port,
                s"/chapters/${chapter.id}/messages?pageSize=1&cursor=$nextCursor",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected first page size to be 1")).unless(firstPage.items.size == 1)
              _ <- ZIO.fail(new RuntimeException("Expected second page size to be 1")).unless(secondPage.items.size == 1)
              _ <- ZIO.fail(new RuntimeException("Expected chapter timeline to return newest message first")).unless(
                firstPage.items.head.id == secondMessage.id
              )
              _ <- ZIO.fail(new RuntimeException("Expected chapter timeline cursor to page to older message")).unless(
                secondPage.items.head.id == firstMessage.id
              )
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("message search supports cursor-based pagination") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("cursor-user", "Secret123!").toJson)
              login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("cursor-user", "Secret123!", "cursor-device").toJson)
              _ <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("cursor hello 1").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("cursor hello 2").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              _ <- postJson[MessageResponse](
                port,
                "/messages",
                CreateMessageRequest("cursor hello 3").toJson,
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              firstPage <- getJson[MessageSearchPage](
                port,
                "/messages/search?q=cursor&pageSize=2",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              nextCursor <- ZIO
                .fromOption(firstPage.nextCursor)
                .orElseFail(new RuntimeException("Expected nextCursor on first page"))
              secondPage <- getJson[MessageSearchPage](
                port,
                s"/messages/search?q=cursor&pageSize=2&cursor=$nextCursor",
                headers = Map("X-Session-Token" -> login.sessionToken)
              )
              firstIds = firstPage.items.map(_.id).toSet
              secondIds = secondPage.items.map(_.id).toSet
              _ <- ZIO.fail(new RuntimeException("Expected first page size to be 2")).unless(firstPage.items.size == 2)
              _ <- ZIO.fail(new RuntimeException("Expected second page size to be 1")).unless(secondPage.items.size == 1)
              _ <- ZIO.fail(new RuntimeException("Paginated pages must not overlap")).unless(firstIds.intersect(secondIds).isEmpty)
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      }
      ).provideShared(jdbcFixtureLayer),
      suite("skunk-runtime")(
        test("chapter timeline and message edit work over live skunk pool") {
          for {
            fixture <- ZIO.service[Fixture]
            _ <- withServer(fixture.routes) { port =>
              for {
                _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("skunk-runtime-user", "Secret123!").toJson)
                login <- postJson[AuthResponse](port, "/auth/login", LoginRequest("skunk-runtime-user", "Secret123!", "skunk-runtime-device").toJson)
                chapter <- postJson[ChapterResponse](
                  port,
                  "/chapters",
                  CreateChapterRequest("Skunk Runtime Chapter", None).toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                firstMessage <- postJson[MessageResponse](
                  port,
                  "/messages",
                  CreateMessageRequest("skunk message 1").toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                secondMessage <- postJson[MessageResponse](
                  port,
                  "/messages",
                  CreateMessageRequest("skunk message 2").toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                _ <- postNoBody(
                  port,
                  s"/chapters/${chapter.id}/messages",
                  AddMessageToChapterRequest(firstMessage.id).toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                _ <- postNoBody(
                  port,
                  s"/chapters/${chapter.id}/messages",
                  AddMessageToChapterRequest(secondMessage.id).toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                edited <- putJson[MessageResponse](
                  port,
                  s"/messages/by-id?messageId=${secondMessage.id}",
                  EditMessageRequest("skunk message 2 edited", expectedVersion = Some(secondMessage.version)).toJson,
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                history <- getJson[List[MessageHistoryEntry]](
                  port,
                  s"/messages/history?messageId=${secondMessage.id}",
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                firstPage <- getJson[MessageSearchPage](
                  port,
                  s"/chapters/${chapter.id}/messages?pageSize=1",
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                nextCursor <- ZIO
                  .fromOption(firstPage.nextCursor)
                  .orElseFail(new RuntimeException("Expected nextCursor on first chapter timeline page"))
                secondPage <- getJson[MessageSearchPage](
                  port,
                  s"/chapters/${chapter.id}/messages?pageSize=1&cursor=$nextCursor",
                  headers = Map("X-Session-Token" -> login.sessionToken)
                )
                _ <- ZIO.fail(new RuntimeException("Expected updated version to be incremented")).unless(edited.version == 2)
                _ <- ZIO.fail(new RuntimeException("Expected one message history entry after one edit")).unless(history.size == 1)
                _ <- ZIO.fail(new RuntimeException("Expected chapter timeline to return newest message first")).unless(
                  firstPage.items.headOption.exists(_.id == secondMessage.id)
                )
                _ <- ZIO.fail(new RuntimeException("Expected chapter timeline cursor to page to older message")).unless(
                  secondPage.items.headOption.exists(_.id == firstMessage.id)
                )
              } yield {
                assertTrue(true)
              }
            }
          } yield {
            assertTrue(true)
          }
        }
      ).provideShared(skunkFixtureLayer)
    )
  }

  private def makeAdmin(db: JdbcDatabase, userId: Long): Task[Unit] = {
    db.withConnection { connection =>
      val statement = connection.prepareStatement("UPDATE users SET is_admin = TRUE WHERE id = ?")
      try {
        statement.setLong(1, userId)
        statement.executeUpdate()
        ()
      } finally {
        statement.close()
      }
    }
  }

  private def withServer[A](routes: Routes[Any, Response])(effect: Int => Task[A]): Task[A] = {
    ZIO.scoped {
      for {
        port <- Server.install(routes)
        result <- effect(port)
      } yield {
        result
      }
    }.provide(Server.defaultWithPort(0))
  }

  private def postJson[A: JsonDecoder](port: Int, path: String, body: String, headers: Map[String, String] = Map.empty): Task[A] = {
    for {
      response <- send(port, "POST", path, Some(body), headers)
      parsed <- ZIO.fromEither(response.fromJson[A]).mapError(error => new RuntimeException(error))
    } yield {
      parsed
    }
  }

  private def putJson[A: JsonDecoder](port: Int, path: String, body: String, headers: Map[String, String] = Map.empty): Task[A] = {
    for {
      response <- send(port, "PUT", path, Some(body), headers)
      parsed <- ZIO.fromEither(response.fromJson[A]).mapError(error => new RuntimeException(error))
    } yield {
      parsed
    }
  }

  private def deleteNoBody(port: Int, path: String, headers: Map[String, String] = Map.empty): Task[Unit] = {
    send(port, "DELETE", path, None, headers).unit
  }

  private def postNoBody(port: Int, path: String, body: String, headers: Map[String, String] = Map.empty): Task[Unit] = {
    val payload = if body.trim.isEmpty then None else Some(body)
    send(port, "POST", path, payload, headers).unit
  }

  private def getJson[A: JsonDecoder](port: Int, path: String, headers: Map[String, String] = Map.empty): Task[A] = {
    for {
      response <- send(port, "GET", path, None, headers)
      parsed <- ZIO.fromEither(response.fromJson[A]).mapError(error => new RuntimeException(error))
    } yield {
      parsed
    }
  }

  private def send(
    port: Int,
    method: String,
    path: String,
    body: Option[String],
    headers: Map[String, String] = Map.empty
  ): Task[String] = {
    ZIO.attemptBlocking {
      val url = new URL(s"http://127.0.0.1:$port$path")
      val connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod(method)
      connection.setRequestProperty("Content-Type", "application/json")
      headers.foreach { case (key, value) =>
        connection.setRequestProperty(key, value)
      }
      body.foreach { payload =>
        connection.setDoOutput(true)
        val os: OutputStream = connection.getOutputStream
        try {
          os.write(payload.getBytes(StandardCharsets.UTF_8))
          os.flush()
        } finally {
          os.close()
        }
      }
      val status = connection.getResponseCode
      val stream = if status >= 200 && status < 300 then connection.getInputStream else connection.getErrorStream
      val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
      val sb = new StringBuilder
      try {
        var line: String = null
        while {
          line = reader.readLine()
          line != null
        } do {
          sb.append(line)
        }
      } finally {
        reader.close()
        connection.disconnect()
      }
      if status >= 200 && status < 300 then {
        sb.toString()
      } else {
        throw new RuntimeException(s"$method $path failed with HTTP $status: ${sb.toString()}")
      }
    }
  }

  private def sendWithHeaders(
    port: Int,
    method: String,
    path: String,
    body: Option[String],
    headers: Map[String, String] = Map.empty
  ): Task[(String, Map[String, String])] = {
    ZIO.attemptBlocking {
      val url = new URL(s"http://127.0.0.1:$port$path")
      val connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod(method)
      connection.setRequestProperty("Content-Type", "application/json")
      headers.foreach { case (key, value) =>
        connection.setRequestProperty(key, value)
      }
      body.foreach { payload =>
        connection.setDoOutput(true)
        val os: OutputStream = connection.getOutputStream
        try {
          os.write(payload.getBytes(StandardCharsets.UTF_8))
          os.flush()
        } finally {
          os.close()
        }
      }
      val status = connection.getResponseCode
      val stream = if status >= 200 && status < 300 then connection.getInputStream else connection.getErrorStream
      val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
      val sb = new StringBuilder
      try {
        var line: String = null
        while {
          line = reader.readLine()
          line != null
        } do {
          sb.append(line)
        }
      } finally {
        reader.close()
      }

      val responseHeaders = connection.getHeaderFields
        .entrySet()
        .toArray
        .collect {
          case entry: java.util.Map.Entry[?, ?]
              if entry.getKey != null && entry.getValue != null && !entry.getValue.asInstanceOf[java.util.List[String]].isEmpty =>
            entry.getKey.toString.toLowerCase -> entry.getValue.asInstanceOf[java.util.List[String]].get(0)
        }
        .toMap

      connection.disconnect()

      if status >= 200 && status < 300 then {
        (sb.toString(), responseHeaders)
      } else {
        throw new RuntimeException(s"$method $path failed with HTTP $status: ${sb.toString()}")
      }
    }
  }
}
