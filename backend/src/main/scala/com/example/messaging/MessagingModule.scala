package com.example.messaging

import com.example.infrastructure.db.Database
import zio.*

import java.time.Instant

object MessagingModule {

  final case class MessageData(
    id: Long,
    authorUserId: Long,
    content: String,
    deleted: Boolean,
    version: Int,
    createdAt: Instant,
    updatedAt: Instant,
    clientEditedAt: Option[Instant]
  )

  final case class MessageHistoryData(
    version: Int,
    previousContent: String,
    newContent: String,
    editedByUserId: Long,
    editedAt: Instant
  )

  final case class AuditEntry(
    id: Long,
    actorUserId: Long,
    action: String,
    targetUserId: Option[Long],
    messageId: Option[Long],
    details: Option[String],
    createdAt: Instant
  )

  final case class CursorPage[T](items: List[T], nextCursor: Option[String])

  object OptimisticConcurrency {
    def matches(expectedVersion: Option[Int], actualVersion: Int): Boolean =
      expectedVersion.forall(_ == actualVersion)

    def nextVersion(currentVersion: Int): Int = currentVersion + 1
  }

  trait MessagingService {
    def createMessage(userId: Long, content: String, clientEditedAtEpochMillis: Option[Long] = None): Task[MessageData]
    def searchMessages(
      requesterUserId: Long,
      query: String,
      targetUserId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[MessageData]]
    def getMessageById(requesterUserId: Long, messageId: Long): Task[MessageData]
    def editMessage(
      editorUserId: Long,
      messageId: Long,
      newContent: String,
      expectedVersion: Option[Int] = None,
      clientEditedAtEpochMillis: Option[Long] = None
    ): Task[MessageData]
    def messageHistory(requesterUserId: Long, messageId: Long): Task[List[MessageHistoryData]]
    def deleteMessage(actorUserId: Long, messageId: Long): Task[Unit]
    def listAuditEntries(
      requesterUserId: Long,
      targetUserId: Option[Long],
      messageId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[AuditEntry]]
  }

  final class LiveMessagingService(db: Database) extends MessagingService {

    override def createMessage(userId: Long, content: String, clientEditedAtEpochMillis: Option[Long]): Task[MessageData] = {
      val trimmed = content.trim
      if trimmed.isEmpty then {
        ZIO.fail(new RuntimeException("Message content cannot be empty"))
      } else {
        db.withConnection { connection =>
          val statement = connection.prepareStatement(
            """
              |INSERT INTO messages(author_user_id, content, client_edited_at)
              |VALUES (?, ?, ?)
              |RETURNING id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
              |""".stripMargin
          )
          try {
            statement.setLong(1, userId)
            statement.setString(2, trimmed)
            clientEditedAtEpochMillis match {
              case Some(value) => statement.setTimestamp(3, java.sql.Timestamp.from(Instant.ofEpochMilli(value)))
              case None => statement.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            }
            val rs = statement.executeQuery()
            rs.next()
            val message = readMessageRow(rs)
            rs.close()
            message
          } finally {
            statement.close()
          }
        }
      }
    }

    override def searchMessages(
      requesterUserId: Long,
      query: String,
      targetUserId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[MessageData]] = {
      val searchText = query.trim
      if searchText.isEmpty then {
        ZIO.succeed(CursorPage(Nil, None))
      } else {
        for {
          parsedCursor <- parseCursor(cursor)
          validatedPageSize <- validatePageSize(pageSize)
          isRequesterAdmin <- isAdmin(requesterUserId)
          _ <- targetUserId match {
            case Some(target) if target != requesterUserId && !isRequesterAdmin =>
              ZIO.fail(new RuntimeException("Admin rights are required to search other users' messages"))
            case _ => ZIO.unit
          }
          page <- db.withConnection { connection =>
            val fetchSize = validatedPageSize + 1
            val (sql, fill) = targetUserId match {
              case Some(target) =>
                (
                  """
                    |SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
                    |FROM messages
                    |WHERE author_user_id = ? AND content ILIKE ? AND (? IS NULL OR id < ?)
                    |ORDER BY id DESC
                    |LIMIT ?
                    |""".stripMargin,
                  (ps: java.sql.PreparedStatement) => {
                    ps.setLong(1, target)
                    ps.setString(2, s"%$searchText%")
                    parsedCursor match {
                      case Some(value) =>
                        ps.setLong(3, value)
                        ps.setLong(4, value)
                      case None =>
                        ps.setNull(3, java.sql.Types.BIGINT)
                        ps.setNull(4, java.sql.Types.BIGINT)
                    }
                    ps.setInt(5, fetchSize)
                  }
                )
              case None =>
                (
                  """
                    |SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
                    |FROM messages
                    |WHERE author_user_id = ? AND content ILIKE ? AND (? IS NULL OR id < ?)
                    |ORDER BY id DESC
                    |LIMIT ?
                    |""".stripMargin,
                  (ps: java.sql.PreparedStatement) => {
                    ps.setLong(1, requesterUserId)
                    ps.setString(2, s"%$searchText%")
                    parsedCursor match {
                      case Some(value) =>
                        ps.setLong(3, value)
                        ps.setLong(4, value)
                      case None =>
                        ps.setNull(3, java.sql.Types.BIGINT)
                        ps.setNull(4, java.sql.Types.BIGINT)
                    }
                    ps.setInt(5, fetchSize)
                  }
                )
            }
            val statement = connection.prepareStatement(sql)
            try {
              fill(statement)
              val rs = statement.executeQuery()
              val items = List.newBuilder[MessageData]
              while rs.next() do {
                items += readMessageRow(rs)
              }
              rs.close()
              paginate(items.result(), validatedPageSize, _.id)
            } finally {
              statement.close()
            }
          }
          _ <- if isRequesterAdmin then {
            val targetForAudit = targetUserId.filter(_ != requesterUserId)
            targetForAudit match {
              case Some(target) =>
                writeAudit(requesterUserId, "admin.search.messages", Some(target), None, Some(s"query=$searchText"))
              case None =>
                ZIO.unit
            }
          } else ZIO.unit
        } yield {
          page
        }
      }
    }

    override def getMessageById(requesterUserId: Long, messageId: Long): Task[MessageData] = {
      for {
        message <- findMessage(messageId)
        isRequesterAdmin <- isAdmin(requesterUserId)
        _ <- authorizeReadOrWrite(requesterUserId, message.authorUserId, isRequesterAdmin)
        _ <- if isRequesterAdmin && requesterUserId != message.authorUserId then {
          writeAudit(requesterUserId, "admin.read.message", Some(message.authorUserId), Some(message.id), None)
        } else ZIO.unit
      } yield {
        message
      }
    }

    override def editMessage(
      editorUserId: Long,
      messageId: Long,
      newContent: String,
      expectedVersion: Option[Int],
      clientEditedAtEpochMillis: Option[Long]
    ): Task[MessageData] = {
      val trimmed = newContent.trim
      if trimmed.isEmpty then {
        ZIO.fail(new RuntimeException("Message content cannot be empty"))
      } else {
        for {
          isEditorAdmin <- isAdmin(editorUserId)
          result <- db.withConnection { connection =>
            val fetch = connection.prepareStatement(
              "SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at FROM messages WHERE id = ?"
            )
            try {
              fetch.setLong(1, messageId)
              val rs = fetch.executeQuery()
              if !rs.next() then {
                rs.close()
                throw new RuntimeException("Message not found")
              }

              val current = readMessageRow(rs)
              rs.close()

              if current.deleted then {
                throw new RuntimeException("Deleted messages cannot be edited")
              }

              if current.authorUserId != editorUserId && !isEditorAdmin then {
                throw new RuntimeException("Only the author or an admin can edit the message")
              }

              if !OptimisticConcurrency.matches(expectedVersion, current.version) then
                throw new RuntimeException(
                  s"Optimistic concurrency conflict: expected version ${expectedVersion.getOrElse(-1)} but found ${current.version}"
                )

              val versionSelect = connection.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM message_edits WHERE message_id = ?"
              )
              val nextVersion = try {
                versionSelect.setLong(1, messageId)
                val versionRs = versionSelect.executeQuery()
                versionRs.next()
                val v = versionRs.getInt(1)
                versionRs.close()
                v
              } finally {
                versionSelect.close()
              }

              val historyInsert = connection.prepareStatement(
                """
                  |INSERT INTO message_edits(message_id, version, previous_content, new_content, edited_by_user_id)
                  |VALUES (?, ?, ?, ?, ?)
                  |""".stripMargin
              )
              try {
                historyInsert.setLong(1, messageId)
                historyInsert.setInt(2, nextVersion)
                historyInsert.setString(3, current.content)
                historyInsert.setString(4, trimmed)
                historyInsert.setLong(5, editorUserId)
                historyInsert.executeUpdate()
              } finally {
                historyInsert.close()
              }

              val update = connection.prepareStatement(
                """
                  |UPDATE messages
                  |SET content = ?,
                  |    updated_at = NOW(),
                  |    version = version + 1,
                  |    client_edited_at = COALESCE(?, client_edited_at)
                  |WHERE id = ?
                  |  AND (? IS NULL OR version = ?)
                  |RETURNING id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
                  |""".stripMargin
              )
              try {
                update.setString(1, trimmed)
                clientEditedAtEpochMillis match {
                  case Some(value) => update.setTimestamp(2, java.sql.Timestamp.from(Instant.ofEpochMilli(value)))
                  case None => update.setNull(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                }
                update.setLong(3, messageId)
                expectedVersion match {
                  case Some(value) =>
                    update.setInt(4, value)
                    update.setInt(5, value)
                  case None =>
                    update.setNull(4, java.sql.Types.INTEGER)
                    update.setNull(5, java.sql.Types.INTEGER)
                }
                val updatedRs = update.executeQuery()
                if !updatedRs.next() then {
                  updatedRs.close()
                  throw new RuntimeException("Optimistic concurrency conflict: message version changed")
                }
                val updated = readMessageRow(updatedRs)
                updatedRs.close()
                (updated, current.authorUserId)
              } finally {
                update.close()
              }
            } finally {
              fetch.close()
            }
          }
          _ <- if isEditorAdmin && editorUserId != result._2 then {
            writeAudit(editorUserId, "admin.write.edit_message", Some(result._2), Some(messageId), None)
          } else ZIO.unit
        } yield {
          result._1
        }
      }
    }

    override def messageHistory(requesterUserId: Long, messageId: Long): Task[List[MessageHistoryData]] = {
      for {
        message <- findMessage(messageId)
        isRequesterAdmin <- isAdmin(requesterUserId)
        _ <- authorizeReadOrWrite(requesterUserId, message.authorUserId, isRequesterAdmin)
        history <- db.withConnection { connection =>
          val statement = connection.prepareStatement(
            """
              |SELECT version, previous_content, new_content, edited_by_user_id, edited_at
              |FROM message_edits
              |WHERE message_id = ?
              |ORDER BY version ASC
              |""".stripMargin
          )
          try {
            statement.setLong(1, messageId)
            val rs = statement.executeQuery()
            val items = List.newBuilder[MessageHistoryData]
            while rs.next() do {
              items += MessageHistoryData(
                version = rs.getInt("version"),
                previousContent = rs.getString("previous_content"),
                newContent = rs.getString("new_content"),
                editedByUserId = rs.getLong("edited_by_user_id"),
                editedAt = rs.getTimestamp("edited_at").toInstant
              )
            }
            rs.close()
            items.result()
          } finally {
            statement.close()
          }
        }
        _ <- if isRequesterAdmin && requesterUserId != message.authorUserId then {
          writeAudit(requesterUserId, "admin.read.message_history", Some(message.authorUserId), Some(messageId), None)
        } else ZIO.unit
      } yield {
        history
      }
    }

    override def deleteMessage(actorUserId: Long, messageId: Long): Task[Unit] = {
      for {
        message <- findMessage(messageId)
        isActorAdmin <- isAdmin(actorUserId)
        _ <- authorizeReadOrWrite(actorUserId, message.authorUserId, isActorAdmin)
        _ <- db.withConnection { connection =>
          val statement = connection.prepareStatement(
            "UPDATE messages SET deleted = TRUE, updated_at = NOW() WHERE id = ?"
          )
          try {
            statement.setLong(1, messageId)
            statement.executeUpdate()
            ()
          } finally {
            statement.close()
          }
        }
        _ <- if isActorAdmin && actorUserId != message.authorUserId then {
          writeAudit(actorUserId, "admin.write.delete_message", Some(message.authorUserId), Some(messageId), None)
        } else ZIO.unit
      } yield {
        ()
      }
    }

    override def listAuditEntries(
      requesterUserId: Long,
      targetUserId: Option[Long],
      messageId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[AuditEntry]] = {
      for {
        parsedCursor <- parseCursor(cursor)
        validatedPageSize <- validatePageSize(pageSize)
        _ <- requireAdmin(requesterUserId)
        page <- db.withConnection { connection =>
          val fetchSize = validatedPageSize + 1
          val byTarget = targetUserId.isDefined
          val byMessage = messageId.isDefined
          val (sql, fill) = (byTarget, byMessage) match {
            case (true, true) =>
              (
                """
                  |SELECT id, actor_user_id, action, target_user_id, message_id, details, created_at
                  |FROM audit_log
                  |WHERE target_user_id = ? AND message_id = ? AND (? IS NULL OR id < ?)
                  |ORDER BY id DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, targetUserId.get)
                  ps.setLong(2, messageId.get)
                    parsedCursor match {
                      case Some(value) =>
                        ps.setLong(3, value)
                        ps.setLong(4, value)
                      case None =>
                        ps.setNull(3, java.sql.Types.BIGINT)
                        ps.setNull(4, java.sql.Types.BIGINT)
                    }
                    ps.setInt(5, fetchSize)
                }
              )
            case (true, false) =>
              (
                """
                  |SELECT id, actor_user_id, action, target_user_id, message_id, details, created_at
                  |FROM audit_log
                  |WHERE target_user_id = ? AND (? IS NULL OR id < ?)
                  |ORDER BY id DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, targetUserId.get)
                    parsedCursor match {
                      case Some(value) =>
                        ps.setLong(2, value)
                        ps.setLong(3, value)
                      case None =>
                        ps.setNull(2, java.sql.Types.BIGINT)
                        ps.setNull(3, java.sql.Types.BIGINT)
                    }
                    ps.setInt(4, fetchSize)
                }
              )
            case (false, true) =>
              (
                """
                  |SELECT id, actor_user_id, action, target_user_id, message_id, details, created_at
                  |FROM audit_log
                  |WHERE message_id = ? AND (? IS NULL OR id < ?)
                  |ORDER BY id DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, messageId.get)
                    parsedCursor match {
                      case Some(value) =>
                        ps.setLong(2, value)
                        ps.setLong(3, value)
                      case None =>
                        ps.setNull(2, java.sql.Types.BIGINT)
                        ps.setNull(3, java.sql.Types.BIGINT)
                    }
                    ps.setInt(4, fetchSize)
                }
              )
            case (false, false) =>
              (
                """
                  |SELECT id, actor_user_id, action, target_user_id, message_id, details, created_at
                  |FROM audit_log
                  |WHERE (? IS NULL OR id < ?)
                  |ORDER BY id DESC
                  |LIMIT ?
                  |""".stripMargin,
                (ps: java.sql.PreparedStatement) => {
                  parsedCursor match {
                    case Some(value) =>
                      ps.setLong(1, value)
                      ps.setLong(2, value)
                    case None =>
                      ps.setNull(1, java.sql.Types.BIGINT)
                      ps.setNull(2, java.sql.Types.BIGINT)
                  }
                  ps.setInt(3, fetchSize)
                }
              )
          }

          val statement = connection.prepareStatement(sql)
          try {
            fill(statement)
            val rs = statement.executeQuery()
            val items = List.newBuilder[AuditEntry]
            while rs.next() do {
              val target = rs.getLong("target_user_id")
              val targetOpt = if rs.wasNull() then None else Some(target)
              val message = rs.getLong("message_id")
              val messageOpt = if rs.wasNull() then None else Some(message)
              items += AuditEntry(
                id = rs.getLong("id"),
                actorUserId = rs.getLong("actor_user_id"),
                action = rs.getString("action"),
                targetUserId = targetOpt,
                messageId = messageOpt,
                details = Option(rs.getString("details")),
                createdAt = rs.getTimestamp("created_at").toInstant
              )
            }
            rs.close()
            paginate(items.result(), validatedPageSize, _.id)
          } finally {
            statement.close()
          }
        }
      } yield {
        page
      }
    }

    private def parseCursor(cursor: Option[String]): Task[Option[Long]] = {
      cursor match {
        case None => ZIO.succeed(None)
        case Some(value) =>
          ZIO
            .attempt(value.trim.toLong)
            .map(Some(_))
            .mapError(_ => new RuntimeException("Invalid cursor"))
      }
    }

    private def validatePageSize(size: Int): Task[Int] = {
      if size >= 1 && size <= 100 then ZIO.succeed(size)
      else ZIO.fail(new RuntimeException("pageSize must be between 1 and 100"))
    }

    private def paginate[T](rows: List[T], pageSize: Int, cursorOf: T => Long): CursorPage[T] = {
      if rows.size > pageSize then {
        val items = rows.take(pageSize)
        CursorPage(items, items.lastOption.map(item => cursorOf(item).toString))
      } else {
        CursorPage(rows, None)
      }
    }

    private def findMessage(messageId: Long): Task[MessageData] = {
      db.withConnection { connection =>
        val statement = connection.prepareStatement(
          "SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at FROM messages WHERE id = ?"
        )
        try {
          statement.setLong(1, messageId)
          val rs = statement.executeQuery()
          if !rs.next() then {
            rs.close()
            throw new RuntimeException("Message not found")
          }
          val message = readMessageRow(rs)
          rs.close()
          message
        } finally {
          statement.close()
        }
      }
    }

    private def readMessageRow(rs: java.sql.ResultSet): MessageData = {
      MessageData(
        id = rs.getLong("id"),
        authorUserId = rs.getLong("author_user_id"),
        content = rs.getString("content"),
        deleted = rs.getBoolean("deleted"),
        version = rs.getInt("version"),
        createdAt = rs.getTimestamp("created_at").toInstant,
        updatedAt = rs.getTimestamp("updated_at").toInstant,
        clientEditedAt = Option(rs.getTimestamp("client_edited_at")).map(_.toInstant)
      )
    }

    private def authorizeReadOrWrite(requesterUserId: Long, ownerUserId: Long, requesterIsAdmin: Boolean): Task[Unit] = {
      if requesterUserId == ownerUserId || requesterIsAdmin then {
        ZIO.unit
      } else {
        ZIO.fail(new RuntimeException("Not allowed"))
      }
    }

    private def requireAdmin(userId: Long): Task[Unit] = {
      for {
        admin <- isAdmin(userId)
        _ <- ZIO.fail(new RuntimeException("Admin rights are required")).unless(admin)
      } yield {
        ()
      }
    }

    private def isAdmin(userId: Long): Task[Boolean] = {
      db.withConnection { connection =>
        val statement = connection.prepareStatement("SELECT is_admin FROM users WHERE id = ?")
        try {
          statement.setLong(1, userId)
          val rs = statement.executeQuery()
          if !rs.next() then {
            rs.close()
            false
          } else {
            val value = rs.getBoolean("is_admin")
            rs.close()
            value
          }
        } finally {
          statement.close()
        }
      }
    }

    private def writeAudit(
      actorUserId: Long,
      action: String,
      targetUserId: Option[Long],
      messageId: Option[Long],
      details: Option[String]
    ): Task[Unit] = {
      db.withConnection { connection =>
        val statement = connection.prepareStatement(
          """
            |INSERT INTO audit_log(actor_user_id, action, target_user_id, message_id, details)
            |VALUES (?, ?, ?, ?, ?)
            |""".stripMargin
        )
        try {
          statement.setLong(1, actorUserId)
          statement.setString(2, action)
          targetUserId match {
            case Some(id) => statement.setLong(3, id)
            case None => statement.setNull(3, java.sql.Types.BIGINT)
          }
          messageId match {
            case Some(id) => statement.setLong(4, id)
            case None => statement.setNull(4, java.sql.Types.BIGINT)
          }
          details match {
            case Some(value) => statement.setString(5, value)
            case None => statement.setNull(5, java.sql.Types.VARCHAR)
          }
          statement.executeUpdate()
          ()
        } finally {
          statement.close()
        }
      }
    }
  }

  val layer: URLayer[Database, MessagingService] = ZLayer {
    ZIO.serviceWith[Database](new LiveMessagingService(_))
  }
}