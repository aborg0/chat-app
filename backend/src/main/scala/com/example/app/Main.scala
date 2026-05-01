package com.example.app

import com.example.auth.AuthModule
import com.example.chapters.ChaptersModule
import com.example.groups.GroupsModule
import com.example.infrastructure.db.{Database, Migrations, SkunkSessionPool}
import com.example.messaging.MessagingModule
import com.example.sessions.SessionsModule
import com.example.messaging.DraftsModule
import com.example.messaging.TypingModule
import zio.*
import zio.http.Server

import java.net.InetSocketAddress

object Main extends ZIOAppDefault {

  override def run: ZIO[Any, Throwable, Unit] = {
    for {
      appConfig <- ZIO.withConfigProvider(AppConfig.provider) {
        ZIO.config(AppConfig.config)
      }
      _ <- Migrations.migrate(appConfig.db.url, appConfig.db.effectiveMigrationUser, appConfig.db.effectiveMigrationPassword)
      skunkLayer =
        if appConfig.db.useSkunkRuntime then
          SkunkSessionPool.layer(appConfig.db.url, appConfig.db.user, appConfig.db.password, appConfig.db.skunkMaxSessions)
        else
          SkunkSessionPool.disabled
      _ <- Console.printLine(s"Starting backend on http://${appConfig.http.host}:${appConfig.http.port}")
      _ <- Console.printLine(s"Database runtime selected: ${appConfig.db.runtime}")
      _ <- Server
        .serve(ApiRoutes.routes)
        .provide(
          Server.defaultWith(_.copy(address = new InetSocketAddress(appConfig.http.host, appConfig.http.port))),
          Database.layer(appConfig.db.url, appConfig.db.user, appConfig.db.password),
          skunkLayer,
          SessionsModule.layer,
          AuthModule.layer,
          MessagingModule.layer,
          ChaptersModule.layer,
          GroupsModule.layer,
          DraftsModule.live,
          TypingModule.live
        )
    } yield ()
  }
}
