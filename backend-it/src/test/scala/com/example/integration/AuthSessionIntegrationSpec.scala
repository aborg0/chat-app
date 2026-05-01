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

object AuthSessionIntegrationSpec extends ZIOSpecDefault {
  final case class Fixture(
    routes: Routes[Any, Response]
  )

  private def fixtureLayer(useSkunk: Boolean): ZLayer[Any, Throwable, Fixture] = ZLayer.scoped {
    for {
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking { val c = new PostgreSQLContainer("postgres:18.3-alpine"); c.start(); c }
      )(c => ZIO.succeed(c.stop()))
      _ <- Migrations.migrate(container.getJdbcUrl, container.getUsername, container.getPassword)
      db       = new JdbcDatabase(container.getJdbcUrl, container.getUsername, container.getPassword)
      dbLayer  = ZLayer.succeed[Database](db)
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
      allLayers = sessLayer ++ authLayer ++ msgLayer ++ chapLayer ++ grpLayer ++ DraftsModule.live ++ TypingModule.live ++ dbLayer
      env      <- allLayers.build
    } yield Fixture(ApiRoutes.routes.provideEnvironment(env))
  }

  private val jdbcFixtureLayer  = fixtureLayer(useSkunk = false)
  private val skunkFixtureLayer = fixtureLayer(useSkunk = true)

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("AuthSessionIntegrationSpec")(
      suite("jdbc-fallback")(
      test("password login + list sessions + logout other devices over HTTP") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              register <- postJson[
                RegisterResponse
              ](port, "/auth/register", RegisterRequest("alice", "Secret123!").toJson)
              loginA <- postJson[
                AuthResponse
              ](port, "/auth/login", LoginRequest("alice", "Secret123!", "device-a").toJson)
              _ <- postJson[
                AuthResponse
              ](port, "/auth/login", LoginRequest("alice", "Secret123!", "device-b").toJson)
              sessionsBefore <- getJson[ActiveSessionsPage](
                port,
                "/sessions",
                headers = Map("X-Session-Token" -> loginA.sessionToken)
              )
              _ <- postNoBody(
                port,
                "/sessions/logout-others",
                LogoutOthersRequest(Some("Secret123!")).toJson,
                headers = Map("X-Session-Token" -> loginA.sessionToken)
              )
              sessionsAfter <- getJson[ActiveSessionsPage](
                port,
                "/sessions",
                headers = Map("X-Session-Token" -> loginA.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Expected 2 sessions before logout")).unless(sessionsBefore.items.size == 2)
              _ <- ZIO.fail(new RuntimeException("Expected 1 session after logout")).unless(sessionsAfter.items.size == 1)
              _ <- ZIO
                .fail(new RuntimeException("Expected to keep current session token"))
                .unless(sessionsAfter.items.head.sessionToken == loginA.sessionToken)
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("social login from two devices maps to same user over HTTP") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              first <- postJson[
                AuthResponse
              ](port, "/auth/social-login", SocialLoginRequest("github", "gh_123", Some("alice-gh"), "device-social-a").toJson)
              second <- postJson[
                AuthResponse
              ](port, "/auth/social-login", SocialLoginRequest("github", "gh_123", Some("alice-gh"), "device-social-b").toJson)
              sessions <- getJson[ActiveSessionsPage](
                port,
                "/sessions",
                headers = Map("X-Session-Token" -> first.sessionToken)
              )
              _ <- ZIO.fail(new RuntimeException("Social logins should map to same user")).unless(first.userId == second.userId)
              _ <- ZIO.fail(new RuntimeException("Expected two active social sessions")).unless(sessions.items.size == 2)
            } yield {
              assertTrue(true)
            }
          }
        } yield {
          assertTrue(true)
        }
      },
      test("session list supports cursor-based pagination") {
        for {
          fixture <- ZIO.service[Fixture]
          _ <- withServer(fixture.routes) { port =>
            for {
              _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("session-cursor", "Secret123!").toJson)
              login1 <- postJson[AuthResponse](port, "/auth/login", LoginRequest("session-cursor", "Secret123!", "device-1").toJson)
              _ <- postJson[AuthResponse](port, "/auth/login", LoginRequest("session-cursor", "Secret123!", "device-2").toJson)
              _ <- postJson[AuthResponse](port, "/auth/login", LoginRequest("session-cursor", "Secret123!", "device-3").toJson)
              firstPage <- getJson[ActiveSessionsPage](
                port,
                "/sessions?pageSize=2",
                headers = Map("X-Session-Token" -> login1.sessionToken)
              )
              nextCursor <- ZIO
                .fromOption(firstPage.nextCursor)
                .orElseFail(new RuntimeException("Expected nextCursor on first session page"))
              encodedCursor = java.net.URLEncoder.encode(nextCursor, StandardCharsets.UTF_8)
              secondPage <- getJson[ActiveSessionsPage](
                port,
                s"/sessions?pageSize=2&cursor=$encodedCursor",
                headers = Map("X-Session-Token" -> login1.sessionToken)
              )
              firstTokens = firstPage.items.map(_.sessionToken).toSet
              secondTokens = secondPage.items.map(_.sessionToken).toSet
              _ <- ZIO.fail(new RuntimeException("Expected first page size to be 2")).unless(firstPage.items.size == 2)
              _ <- ZIO.fail(new RuntimeException("Expected second page size to be at least 1")).unless(secondPage.items.nonEmpty)
              _ <- ZIO.fail(new RuntimeException("Paginated session pages must not overlap")).unless(firstTokens.intersect(secondTokens).isEmpty)
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
        test("password + social sessions work over live skunk pool") {
          for {
            fixture <- ZIO.service[Fixture]
            _ <- withServer(fixture.routes) { port =>
              for {
                _ <- postJson[RegisterResponse](port, "/auth/register", RegisterRequest("skunk-auth-user", "Secret123!").toJson)
                passwordA <- postJson[AuthResponse](port, "/auth/login", LoginRequest("skunk-auth-user", "Secret123!", "skunk-device-a").toJson)
                _ <- postJson[AuthResponse](port, "/auth/login", LoginRequest("skunk-auth-user", "Secret123!", "skunk-device-b").toJson)
                socialA <- postJson[AuthResponse](
                  port,
                  "/auth/social-login",
                  SocialLoginRequest("github", "gh_skunk_123", Some("skunk-gh"), "skunk-social-a").toJson
                )
                socialB <- postJson[AuthResponse](
                  port,
                  "/auth/social-login",
                  SocialLoginRequest("github", "gh_skunk_123", Some("skunk-gh"), "skunk-social-b").toJson
                )
                beforeLogout <- getJson[ActiveSessionsPage](
                  port,
                  "/sessions?pageSize=20",
                  headers = Map("X-Session-Token" -> passwordA.sessionToken)
                )
                socialSessions <- getJson[ActiveSessionsPage](
                  port,
                  "/sessions?pageSize=20",
                  headers = Map("X-Session-Token" -> socialA.sessionToken)
                )
                _ <- postNoBody(
                  port,
                  "/sessions/logout-others",
                  LogoutOthersRequest(Some("Secret123!")).toJson,
                  headers = Map("X-Session-Token" -> passwordA.sessionToken)
                )
                afterLogout <- getJson[ActiveSessionsPage](
                  port,
                  "/sessions?pageSize=20",
                  headers = Map("X-Session-Token" -> passwordA.sessionToken)
                )
                _ <- ZIO.fail(new RuntimeException("Expected social logins to map to same user")).unless(socialA.userId == socialB.userId)
                _ <- ZIO.fail(new RuntimeException("Expected two password sessions before logout-others")).unless(beforeLogout.items.size == 2)
                _ <- ZIO.fail(new RuntimeException("Expected two social sessions for social identity user")).unless(socialSessions.items.size == 2)
                _ <- ZIO.fail(new RuntimeException("Expected only current session after logout-others")).unless(afterLogout.items.size == 1)
                _ <- ZIO.fail(new RuntimeException("Expected current session token to remain active")).unless(
                  afterLogout.items.head.sessionToken == passwordA.sessionToken
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

  private def postNoBody(port: Int, path: String, body: String, headers: Map[String, String] = Map.empty): Task[Unit] = {
    send(port, "POST", path, Some(body), headers).unit
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
}
