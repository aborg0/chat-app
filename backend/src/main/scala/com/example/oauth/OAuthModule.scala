package com.example.oauth

import zio.*
import zio.json.*
import scala.util.Try
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import com.example.infrastructure.db.Database
import com.example.sessions.SessionsModule.SessionsService
import com.example.app.{AppConfig, OAuthProviderConfig}

object OAuthModule {

  // -------- DTOs for OAuth Provider Responses --------

  final case class OAuthTokenResponse(
    access_token: String,
    id_token: String,
    token_type: String,
    expires_in: Int
  )
  object OAuthTokenResponse {
    given JsonDecoder[OAuthTokenResponse] = DeriveJsonDecoder.gen
  }

  final case class OAuthUserInfo(
    sub: String,
    email: Option[String],
    name: Option[String],
    picture: Option[String],
    email_verified: Option[Boolean]
  )
  object OAuthUserInfo {
    given JsonDecoder[OAuthUserInfo] = DeriveJsonDecoder.gen
  }

  final case class JwtClaims(
    sub: String,
    email: Option[String],
    name: Option[String],
    picture: Option[String],
    email_verified: Option[Boolean],
    aud: String,
    iss: String,
    exp: Long,
    iat: Long,
    nonce: Option[String]
  )
  object JwtClaims {
    given JsonDecoder[JwtClaims] = DeriveJsonDecoder.gen
  }

  final case class AuthResult(userId: Long, sessionToken: String)

  // -------- OAuth State and Challenge Storage --------

  private final case class OAuthChallenge(
    state: String,
    codeChallenge: String,
    createdAtMs: Long
  )

  // Simple in-memory cache for OAuth challenges (32-second TTL).
  // In production, use Redis for distributed deployments.
  private class OAuthCache {
    private val cache = scala.collection.concurrent.TrieMap[String, OAuthChallenge]()
    private val ttlMs = 32000L

    def store(state: String, codeChallenge: String): Unit = {
      val challenge = OAuthChallenge(state, codeChallenge, java.lang.System.currentTimeMillis())
      cache.put(state, challenge)
    }

    def retrieve(state: String): Option[String] = {
      cache.get(state).flatMap { challenge =>
        val age = java.lang.System.currentTimeMillis() - challenge.createdAtMs
        if age > ttlMs then {
          cache.remove(state)
          None
        } else {
          Some(challenge.codeChallenge)
        }
      }
    }

    def cleanup(): Unit = {
      val now = java.lang.System.currentTimeMillis()
      cache.filterInPlace { case (_, challenge) =>
        (now - challenge.createdAtMs) <= ttlMs
      }
    }
  }

  // -------- PKCE Helpers --------

  private object PKCE {
    def generateCodeVerifier(): String = {
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }

    def generateCodeChallenge(codeVerifier: String): String = {
      val bytes = codeVerifier.getBytes("UTF-8")
      val digest = MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(hash)
    }

    def generateState(): String = {
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }
  }

  // -------- JWT Verification --------

  // For GitHub, which doesn't provide a JWKS endpoint, we fetch the public key
  // from GitHub's API or use a hardcoded key. This is a simplified implementation
  // that validates the basic JWT structure; production should use proper JWT libraries.
  private object JwtVerifier {
    def verifyAndDecodeGitHub(idToken: String, expectedAudience: String): Task[JwtClaims] = {
      ZIO.attempt {
        // Simple JWT decode (3 parts: header.payload.signature)
        val parts = idToken.split("\\.")
        if parts.length != 3 then throw new RuntimeException("Invalid JWT format")

        val payload = new String(Base64.getUrlDecoder.decode(parts(1)), "UTF-8")
        val claims = payload.fromJson[JwtClaims] match {
          case Left(parseErr) => throw new RuntimeException(s"Failed to parse JWT claims: $parseErr")
          case Right(claimsData) => claimsData
        }

        // Basic validation (in production, verify signature using provider's public key)
        if claims.aud != expectedAudience then throw new RuntimeException("Invalid JWT audience")
        if claims.exp < java.lang.System.currentTimeMillis() / 1000 then throw new RuntimeException("JWT token expired")

        claims
      }
    }
  }

  // -------- OAuth Service --------

  trait OAuthService {
    def initiateLogin(provider: String, deviceId: String): Task[(String, String)] // (redirectUri, state)
    def exchangeCode(
      provider: String,
      code: String,
      state: String,
      codeVerifier: String,
      deviceId: String
    ): Task[AuthResult]
  }

  final class LiveOAuthService(
    db: Database,
    sessionsService: SessionsService,
    appConfig: AppConfig
  ) extends OAuthService {
    private val cache = new OAuthCache()

    override def initiateLogin(provider: String, deviceId: String): Task[(String, String)] = {
      for {
        normalizedProvider <- validateProvider(provider)
        providerConfig <- ZIO.fromOption(appConfig.oauth.providers.get(normalizedProvider))
          .orElseFail(new RuntimeException(s"Provider $normalizedProvider not configured"))
        state <- ZIO.succeed(PKCE.generateState())
        codeVerifier <- ZIO.succeed(PKCE.generateCodeVerifier())
        codeChallenge <- ZIO.succeed(PKCE.generateCodeChallenge(codeVerifier))
        _ <- ZIO.succeed(cache.store(state, codeVerifier)) // Store verifier under state key
        redirectUri <- ZIO.succeed {
          val params = List(
            "client_id" -> providerConfig.clientId,
            "redirect_uri" -> "http://localhost:8080/oauth/callback",
            "scope" -> "openid profile email",
            "response_type" -> "code",
            "state" -> state,
            "code_challenge" -> codeChallenge,
            "code_challenge_method" -> "S256"
          )
          providerConfig.authorizationUri + "?" + params.map { case (k, v) =>
            s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
          }.mkString("&")
        }
      } yield {
        (redirectUri, state)
      }
    }

    override def exchangeCode(
      provider: String,
      code: String,
      state: String,
      codeVerifier: String,
      deviceId: String
    ): Task[AuthResult] = {
      for {
        normalizedProvider <- validateProvider(provider)
        providerConfig <- ZIO.fromOption(appConfig.oauth.providers.get(normalizedProvider))
          .orElseFail(new RuntimeException(s"Provider $normalizedProvider not configured"))
        // Verify state matches stored state and code_verifier is valid
        storedCodeVerifier <- ZIO.fromOption(cache.retrieve(state))
          .orElseFail(new RuntimeException("Invalid or expired OAuth state"))
        _ <- ZIO.fail(new RuntimeException("Code verifier mismatch")).unless(codeVerifier == storedCodeVerifier)
        // Exchange authorization code for token
        tokenResponse <- exchangeCodeForToken(normalizedProvider, providerConfig, code, codeVerifier)
        // Verify and decode ID token
        claims <- JwtVerifier.verifyAndDecodeGitHub(tokenResponse.id_token, providerConfig.clientId)
        // Look up or create user from verified claims
        userId <- findOrCreateOAuthUser(normalizedProvider, claims)
        // Create session
        sessionToken <- sessionsService.createSession(userId, deviceId)
      } yield {
        AuthResult(userId, sessionToken)
      }
    }

    private def exchangeCodeForToken(
      provider: String,
      config: OAuthProviderConfig,
      code: String,
      codeVerifier: String
    ): Task[OAuthTokenResponse] = {
      for {
        // Build form-encoded request body
        formData = List(
          "client_id" -> config.clientId,
          "client_secret" -> config.clientSecret,
          "code" -> code,
          "code_verifier" -> codeVerifier,
          "grant_type" -> "authorization_code",
          "redirect_uri" -> "http://localhost:8080/oauth/callback"
        ).map { case (k, v) =>
          s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }.mkString("&")
        
        // Make HTTP POST request using ZIO's built-in HTTP or fall back to manual
        // For simplicity, we'll create a future-based HTTP call
        // In production, use a proper HTTP client integration
        result <- ZIO.attempt {
          // This is a placeholder - in production use a ZIO HTTP client
          // For now, we'll throw an error to indicate this needs proper implementation
          throw new RuntimeException("OAuth token exchange not yet implemented with zio-http")
        }
      } yield {
        result
      }
    }

    private def findOrCreateOAuthUser(
      provider: String,
      claims: JwtClaims
    ): Task[Long] = {
      db.withConnection { connection =>
        val lookup = connection.prepareStatement(
          "SELECT user_id FROM auth_identities WHERE provider = ? AND provider_user_id = ?"
        )
        try {
          lookup.setString(1, provider)
          lookup.setString(2, claims.sub)
          val rs = lookup.executeQuery()
          if rs.next() then {
            val existing = rs.getLong("user_id")
            rs.close()
            existing
          } else {
            rs.close()
            // Create new user from OAuth claims
            val displayName = claims.name.getOrElse(s"${provider}_${claims.sub}").take(180)

            val createUser = connection.prepareStatement(
              "INSERT INTO users(username, password_hash) VALUES (?, NULL) RETURNING id"
            )
            val userId = try {
              createUser.setString(1, displayName)
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
              link.setString(3, claims.sub)
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

    private def validateProvider(provider: String): Task[String] = {
      val normalized = provider.trim.toLowerCase
      val supported = Set("github", "google", "microsoft", "apple", "discord")
      if supported.contains(normalized) then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("Unsupported social login provider"))
    }
  }

  val layer: URLayer[Database & SessionsService & AppConfig, OAuthService] = ZLayer {
    for {
      db <- ZIO.service[Database]
      sessions <- ZIO.service[SessionsService]
      config <- ZIO.service[AppConfig]
    } yield new LiveOAuthService(db, sessions, config)
  }
}
