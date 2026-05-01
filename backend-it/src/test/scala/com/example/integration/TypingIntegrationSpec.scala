package com.example.integration

import com.example.messaging.TypingModule
import com.example.infrastructure.db.{Database, JdbcDatabase, Migrations, SkunkSessionPool}
import org.testcontainers.containers.PostgreSQLContainer
import zio.*
import zio.test.*

object TypingIntegrationSpec extends ZIOSpecDefault {

  private def fixtureLayer: ZLayer[Any, Throwable, TypingModule.TypingService] = ZLayer.scoped {
    for {
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking { val c = new PostgreSQLContainer("postgres:18.3-alpine"); c.start(); c }
      )(c => ZIO.succeed(c.stop()))
      _ <- Migrations.migrate(container.getJdbcUrl, container.getUsername, container.getPassword)
      db = new JdbcDatabase(container.getJdbcUrl, container.getUsername, container.getPassword)
      dbLayer = ZLayer.succeed[Database](db)
      env <- (dbLayer >>> TypingModule.live).build
      service = env.get[TypingModule.TypingService]
    } yield service
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("TypingIntegrationSpec")(
      test("typing service tracks user typing state across chapters") {
        for {
          typingSvc <- ZIO.service[TypingModule.TypingService]
          // Test chapter 1: alice and bob typing
          _ <- typingSvc.startTyping(1L, "alice", 100L)
          users1 <- typingSvc.getTypingUsers(100L)
          _ <- ZIO
            .fail(new RuntimeException(s"Expected alice in typing users for chapter 100, got $users1"))
            .unless(users1.exists(_.username == "alice"))
          // Add bob
          _ <- typingSvc.startTyping(2L, "bob", 100L)
          users2 <- typingSvc.getTypingUsers(100L)
          _ <- ZIO
            .fail(new RuntimeException(s"Expected both alice and bob typing, got $users2"))
            .unless(users2.length == 2 && users2.map(_.username).toSet == Set("alice", "bob"))
          // Stop alice
          _ <- typingSvc.stopTyping(1L, 100L)
          users3 <- typingSvc.getTypingUsers(100L)
          _ <- ZIO
            .fail(new RuntimeException(s"Expected only bob typing after alice stops, got $users3"))
            .unless(users3.length == 1 && users3.exists(_.username == "bob"))
          // Test chapter 2: alice typing in different chapter
          _ <- typingSvc.startTyping(1L, "alice", 200L)
          chap2Users <- typingSvc.getTypingUsers(200L)
          _ <- ZIO
            .fail(new RuntimeException(s"Expected alice in chapter 200, got $chap2Users"))
            .unless(chap2Users.exists(_.username == "alice"))
          // Verify chapter 1 still only has bob
          chap1UsersStill <- typingSvc.getTypingUsers(100L)
          _ <- ZIO
            .fail(new RuntimeException(s"Chapter 100 should still only have bob, got $chap1UsersStill"))
            .unless(chap1UsersStill.length == 1 && chap1UsersStill.exists(_.username == "bob"))
        } yield {
          assertTrue(true)
        }
      }
    ).provideShared(fixtureLayer)
  }
}
