package com.example.app

import zio.*
import zio.config.typesafe.TypesafeConfigProvider

final case class DbConfig(
  url: String,
  user: String,
  password: String,
  migrationUser: Option[String],
  migrationPassword: Option[String]
)
final case class HttpConfig(host: String, port: Int)

final case class OAuthProviderConfig(
  clientId: String,
  clientSecret: String,
  authorizationUri: String,
  tokenUri: String,
  jwksUri: Option[String],
  userInfoUri: Option[String]
)

final case class OAuthConfig(
  providers: Map[String, OAuthProviderConfig]
)

final case class AppConfig(db: DbConfig, http: HttpConfig, oauth: OAuthConfig)

extension (db: DbConfig) {
  def effectiveMigrationUser: String     = db.migrationUser.getOrElse(db.user)
  def effectiveMigrationPassword: String = db.migrationPassword.getOrElse(db.password)
}

object AppConfig {
  private val dbConfig: Config[DbConfig] = {
    (
      Config.string("url") ++
        Config.string("user") ++
        Config.string("password") ++
        Config.string("migrationUser").optional ++
        Config.string("migrationPassword").optional
    ).nested("db").map {
      case (url, user, password, migUser, migPassword) =>
        DbConfig(url, user, password, migUser, migPassword)
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

  private val oauthProviderConfig: Config[OAuthProviderConfig] = {
    (
      Config.string("clientId") ++
        Config.string("clientSecret") ++
        Config.string("authorizationUri") ++
        Config.string("tokenUri") ++
        Config.string("jwksUri").optional ++
        Config.string("userInfoUri").optional
    ).map {
      case (clientId, clientSecret, authUri, tokenUri, jwksUri, userInfoUri) =>
        OAuthProviderConfig(clientId, clientSecret, authUri, tokenUri, jwksUri, userInfoUri)
    }
  }

  private val oauthConfig: Config[OAuthConfig] = {
    // For now, we'll provide a default empty OAuthConfig
    // In the future, this can be extended to read from config if needed
    Config.succeed(OAuthConfig(Map()))
  }

  val config: Config[AppConfig] = {
    (dbConfig ++ httpConfig ++ oauthConfig).map {
      case (db, http, oauth) => AppConfig(db, http, oauth)
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
