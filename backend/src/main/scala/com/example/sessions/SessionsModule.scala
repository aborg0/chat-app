package com.example.sessions

import zio.*
import com.example.infrastructure.db.Database

import java.time.Instant
import java.util.UUID

object SessionsModule {

  final case class SessionData(sessionToken: String, deviceId: String, createdAt: Instant)
  final case class CursorPage[T](items: List[T], nextCursor: Option[String])
  final case class SessionCursor(createdAt: Instant, sessionToken: String)

  trait SessionsService {
    def createSession(userId: Long, deviceId: String): Task[String]
    def getActiveSessions(userId: Long, cursor: Option[String], pageSize: Int): Task[CursorPage[SessionData]]
    def logoutFromOtherDevices(userId: Long, currentSessionToken: String): Task[Unit]
    def requireActiveUser(sessionToken: String): Task[Long]
  }

  final class LiveSessionsService(db: Database) extends SessionsService {
    override def createSession(userId: Long, deviceId: String): Task[String] = {
      val token = UUID.randomUUID().toString
      db.withConnection { connection =>
        val statement = connection.prepareStatement(
          "INSERT INTO sessions(session_token, user_id, device_id, active) VALUES (?, ?, ?, TRUE)"
        )
        try {
          statement.setString(1, token)
          statement.setLong(2, userId)
          statement.setString(3, deviceId)
          statement.executeUpdate()
          token
        } finally {
          statement.close()
        }
      }
    }

    override def getActiveSessions(userId: Long, cursor: Option[String], pageSize: Int): Task[CursorPage[SessionData]] = {
      for {
        parsedCursor <- parseCursor(cursor)
        validatedPageSize <- validatePageSize(pageSize)
        page <- db.withConnection { connection =>
          val fetchSize = validatedPageSize + 1
          val (sql, bind) = parsedCursor match {
            case Some(value) =>
              (
                """
                  |SELECT session_token, device_id, created_at
                  |FROM sessions
                  |WHERE user_id = ? AND active = TRUE
                  |  AND (created_at < ? OR (created_at = ? AND session_token < ?))
                  |ORDER BY created_at DESC, session_token DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, userId)
                  val ts = java.sql.Timestamp.from(value.createdAt)
                  ps.setTimestamp(2, ts)
                  ps.setTimestamp(3, ts)
                  ps.setString(4, value.sessionToken)
                  ps.setInt(5, fetchSize)
                }
              )
            case None =>
              (
                """
                  |SELECT session_token, device_id, created_at
                  |FROM sessions
                  |WHERE user_id = ? AND active = TRUE
                  |ORDER BY created_at DESC, session_token DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, userId)
                  ps.setInt(2, fetchSize)
                }
              )
          }
          val statement = connection.prepareStatement(sql)
          try {
            bind(statement)

            val rs = statement.executeQuery()
            val sessions = List.newBuilder[SessionData]
            while rs.next() do {
              sessions += SessionData(
                rs.getString("session_token"),
                rs.getString("device_id"),
                rs.getTimestamp("created_at").toInstant
              )
            }
            rs.close()
            paginate(sessions.result(), validatedPageSize)
          } finally {
            statement.close()
          }
        }
      } yield {
        page
      }
    }

    override def logoutFromOtherDevices(userId: Long, currentSessionToken: String): Task[Unit] = {
      db.withConnection { connection =>
        val statement = connection.prepareStatement(
          "UPDATE sessions SET active = FALSE WHERE user_id = ? AND session_token <> ?"
        )
        try {
          statement.setLong(1, userId)
          statement.setString(2, currentSessionToken)
          statement.executeUpdate()
          ()
        } finally {
          statement.close()
        }
      }
    }

    override def requireActiveUser(sessionToken: String): Task[Long] = {
      db.withConnection { connection =>
        val statement = connection.prepareStatement(
          "SELECT user_id FROM sessions WHERE session_token = ? AND active = TRUE"
        )
        try {
          statement.setString(1, sessionToken)
          val rs = statement.executeQuery()
          if !rs.next() then {
            rs.close()
            throw new RuntimeException("Invalid or inactive session token")
          }
          val userId = rs.getLong("user_id")
          rs.close()
          userId
        } finally {
          statement.close()
        }
      }
    }

    private def parseCursor(cursor: Option[String]): Task[Option[SessionCursor]] = {
      cursor match {
        case None => ZIO.succeed(None)
        case Some(value) =>
          ZIO.attempt {
            val parts = value.trim.split("\\|", 2)
            if parts.length != 2 || parts(1).isBlank then {
              throw new RuntimeException("Invalid cursor")
            }
            SessionCursor(
              createdAt = Instant.ofEpochMilli(parts(0).toLong),
              sessionToken = parts(1)
            )
          }.map(Some(_)).mapError(_ => new RuntimeException("Invalid cursor"))
      }
    }

    private def validatePageSize(size: Int): Task[Int] = {
      if size >= 1 && size <= 100 then ZIO.succeed(size)
      else ZIO.fail(new RuntimeException("pageSize must be between 1 and 100"))
    }

    private def paginate(rows: List[SessionData], pageSize: Int): CursorPage[SessionData] = {
      if rows.size > pageSize then {
        val items = rows.take(pageSize)
        CursorPage(items, items.lastOption.map(item => s"${item.createdAt.toEpochMilli}|${item.sessionToken}"))
      } else {
        CursorPage(rows, None)
      }
    }
  }

  val layer: URLayer[Database, SessionsService] = ZLayer {
    ZIO.serviceWith[Database](new LiveSessionsService(_))
  }
}