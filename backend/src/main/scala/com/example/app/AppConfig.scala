package com.example.app

import zio.*
import zio.config.typesafe.TypesafeConfigProvider

final case class DbConfig(url: String, user: String, password: String)
final case class HttpConfig(host: String, port: Int)
final case class AppConfig(db: DbConfig, http: HttpConfig)

object AppConfig {
  private val dbConfig: Config[DbConfig] = {
    (
      Config.string("url") ++
        Config.string("user") ++
        Config.string("password")
    ).nested("db").map {
      case (url, user, password) => DbConfig(url, user, password)
    }
  }

  private val httpConfig: Config[HttpConfig] = {
    (
      Config.string("host") ++
        Config.int("port")
    ).nested("http").map {
      case (host, port) => HttpConfig(host, port)
    }
  }

  val config: Config[AppConfig] = {
    (dbConfig ++ httpConfig).map {
      case (db, http) => AppConfig(db, http)
    }
  }

  // Environment variables (e.g. DB_URL) take priority over application.conf.
  // The env provider uses underscore-separated uppercase keys mapped to
  // the nested path: DB_URL → db.url, DB_USER → db.user, etc.
  val provider: ConfigProvider =
    ConfigProvider
      .envProvider
      .orElse(TypesafeConfigProvider.fromResourcePath())
}
