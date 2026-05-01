package com.example.auth

import zio.*
import com.example.infrastructure.db.Database
import com.example.sessions.SessionsModule.SessionsService

import de.mkammerer.argon2.Argon2Factory

object AuthModule {

  private val UsernameRegex = "^[a-zA-Z0-9._-]{3,64}$".r
  private val SupportedSocialProviders = Set("github", "google", "microsoft", "apple", "discord")

  final case class AuthResult(userId: Long, sessionToken: String)

  object Passwords {
    def hash(password: String): Task[String] = {
      ZIO.attempt {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        argon2.hash(3, 65536, 1, password.toCharArray)
      }
    }

    def verify(password: String, hashValue: String): Task[Boolean] = {
      ZIO.attempt {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        argon2.verify(hashValue, password.toCharArray)
      }
    }
  }

  trait AuthService {
    def registerPasswordUser(username: String, password: String): Task[Long]
    def loginPassword(username: String, password: String, deviceId: String): Task[AuthResult]
    def socialLogin(provider: String, providerUserId: String, displayName: Option[String], deviceId: String): Task[AuthResult]
    def reauthenticate(userId: Long, password: String): Task[Unit]
  }

  final class LiveAuthService(db: Database, sessionsService: SessionsService) extends AuthService {
    override def registerPasswordUser(username: String, password: String): Task[Long] = {
      for {
        _ <- validateUsername(username)
        _ <- validatePassword(password)
        passwordHash <- Passwords.hash(password)
        userId <- db.withConnection { connection =>
          val statement = connection.prepareStatement("INSERT INTO users(username, password_hash) VALUES (?, ?) RETURNING id")
          try {
            statement.setString(1, username.trim)
            statement.setString(2, passwordHash)
            val rs = statement.executeQuery()
            rs.next()
            val id = rs.getLong(1)
            rs.close()
            id
          } finally {
            statement.close()
          }
        }.mapError(toRegistrationError)
      } yield {
        userId
      }
    }

    override def loginPassword(username: String, password: String, deviceId: String): Task[AuthResult] = {
      for {
        _ <- validateUsername(username)
        _ <- validatePassword(password)
        _ <- validateDeviceId(deviceId)
        user <- db.withConnection { connection =>
          val statement = connection.prepareStatement("SELECT id, password_hash FROM users WHERE username = ?")
          try {
            statement.setString(1, username.trim)
            val rs = statement.executeQuery()
            if !rs.next() then {
              rs.close()
              throw new RuntimeException("Invalid credentials")
            }
            val row = (rs.getLong("id"), Option(rs.getString("password_hash")))
            rs.close()
            row
          } finally {
            statement.close()
          }
        }
        userId = user._1
        hashValueOpt = user._2
        hashValue <- ZIO.fromOption(hashValueOpt).orElseFail(new RuntimeException("Invalid credentials"))
        valid <- Passwords.verify(password, hashValue)
        _ <- ZIO.fail(new RuntimeException("Invalid credentials")).unless(valid)
        sessionToken <- sessionsService.createSession(userId, deviceId)
      } yield {
        AuthResult(userId, sessionToken)
      }
    }

    override def socialLogin(provider: String, providerUserId: String, displayName: Option[String], deviceId: String): Task[AuthResult] = {
      for {
        normalizedProvider <- validateProvider(provider)
        normalizedProviderUserId <- validateProviderUserId(providerUserId)
        _ <- validateDeviceId(deviceId)
        normalizedDisplayName = displayName.map(_.trim).filter(_.nonEmpty).map(_.take(180))
        userId <- findOrCreateSocialIdentity(normalizedProvider, normalizedProviderUserId, normalizedDisplayName)
        sessionToken <- sessionsService.createSession(userId, deviceId)
      } yield {
        AuthResult(userId, sessionToken)
      }
    }

    override def reauthenticate(userId: Long, password: String): Task[Unit] = {
      for {
        hashValueOpt <- db.withConnection { connection =>
          val statement = connection.prepareStatement("SELECT password_hash FROM users WHERE id = ?")
          try {
            statement.setLong(1, userId)
            val rs = statement.executeQuery()
            val value = if rs.next() then Option(rs.getString("password_hash")) else None
            rs.close()
            value
          } finally {
            statement.close()
          }
        }
        hashValue <- ZIO.fromOption(hashValueOpt).orElseFail(new RuntimeException("No password configured for user"))
        valid <- Passwords.verify(password, hashValue)
        _ <- ZIO.fail(new RuntimeException("Re-authentication failed")).unless(valid)
      } yield {
        ()
      }
    }

    private def findOrCreateSocialIdentity(provider: String, providerUserId: String, displayName: Option[String]): Task[Long] = {
      db.withConnection { connection =>
        val lookup = connection.prepareStatement(
          "SELECT user_id FROM auth_identities WHERE provider = ? AND provider_user_id = ?"
        )
        try {
          lookup.setString(1, provider)
          lookup.setString(2, providerUserId)
          val rs = lookup.executeQuery()
          if rs.next() then {
            val existing = rs.getLong("user_id")
            rs.close()
            existing
          } else {
            rs.close()
            val username = displayName.filter(_.nonEmpty).getOrElse(s"${provider}_${providerUserId}").take(180)

            val createUser = connection.prepareStatement(
              "INSERT INTO users(username, password_hash) VALUES (?, NULL) RETURNING id"
            )
            val userId = try {
              createUser.setString(1, username)
              val created = createUser.executeQuery()
              created.next()
              val id = created.getLong(1)
              created.close()
              id
            } finally {
              createUser.close()
            }

            val link = connection.prepareStatement(
              "INSERT INTO auth_identities(user_id, provider, provider_user_id) VALUES (?, ?, ?)"
            )
            try {
              link.setLong(1, userId)
              link.setString(2, provider)
              link.setString(3, providerUserId)
              link.executeUpdate()
            } finally {
              link.close()
            }
            userId
          }
        } finally {
          lookup.close()
        }
      }
    }

    private def validateUsername(username: String): Task[String] = {
      val normalized = username.trim
      if UsernameRegex.matches(normalized) then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("username must match ^[a-zA-Z0-9._-]{3,64}$"))
    }

    private def validatePassword(password: String): Task[Unit] = {
      val hasLetter = password.exists(_.isLetter)
      val hasDigit = password.exists(_.isDigit)
      if password.length >= 8 && password.length <= 200 && hasLetter && hasDigit then ZIO.unit
      else ZIO.fail(new RuntimeException("password must be 8-200 characters and include at least one letter and one number"))
    }

    private def validateDeviceId(deviceId: String): Task[String] = {
      val normalized = deviceId.trim
      if normalized.nonEmpty && normalized.length <= 200 then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("deviceId must be 1-200 characters"))
    }

    private def validateProvider(provider: String): Task[String] = {
      val normalized = provider.trim.toLowerCase
      if SupportedSocialProviders.contains(normalized) then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("Unsupported social login provider"))
    }

    private def validateProviderUserId(providerUserId: String): Task[String] = {
      val normalized = providerUserId.trim
      if normalized.nonEmpty && normalized.length <= 255 then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("providerUserId must be 1-255 characters"))
    }

    private def toRegistrationError(error: Throwable): Throwable = {
      val message = Option(error.getMessage).getOrElse("")
      if message.toLowerCase.contains("duplicate") || message.toLowerCase.contains("unique") then {
        new RuntimeException("Username already exists")
      } else {
        error
      }
    }
  }

  val layer: URLayer[Database & SessionsService, AuthService] = ZLayer {
    for {
      db       <- ZIO.service[Database]
      sessions <- ZIO.service[SessionsService]
    } yield new LiveAuthService(db, sessions)
  }
}