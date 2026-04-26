package com.example.app

import com.example.auth.AuthModule
import com.example.chapters.ChaptersModule
import com.example.groups.GroupsModule
import com.example.infrastructure.db.{Database, Migrations}
import com.example.messaging.MessagingModule
import com.example.sessions.SessionsModule
import zio.*
import zio.http.Server

import java.net.InetSocketAddress

object Main extends ZIOAppDefault {

  override def run: ZIO[Any, Throwable, Unit] = {
    for {
      appConfig <- ZIO.withConfigProvider(AppConfig.provider) {
        ZIO.config(AppConfig.config)
      }
      _ <- Migrations.migrate(appConfig.db.url, appConfig.db.user, appConfig.db.password)
      _ <- Console.printLine(s"Starting backend on http://${appConfig.http.host}:${appConfig.http.port}")
      _ <- Server
        .serve(ApiRoutes.routes)
        .provide(
          Server.defaultWith(_.copy(address = new InetSocketAddress(appConfig.http.host, appConfig.http.port))),
          Database.layer(appConfig.db.url, appConfig.db.user, appConfig.db.password),
          SessionsModule.layer,
          AuthModule.layer,
          MessagingModule.layer,
          ChaptersModule.layer,
          GroupsModule.layer
        )
    } yield ()
  }
}
