package com.example.messaging

import com.example.infrastructure.db.{Database, SkunkSessionPool}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import zio.*
import zio.interop.catz.*

import java.time.{Instant, ZoneOffset}

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

  final class LiveMessagingService(db: Database, skunkPool: SkunkSessionPool) extends MessagingService {

    private val instantCodec    = timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))
    private val optInstantCodec = timestamptz.opt.imap(_.map(_.toInstant))(_.map(_.atOffset(ZoneOffset.UTC)))

    private val messageRowCodec =
      int8 *: int8 *: text *: bool *: int4 *: instantCodec *: instantCodec *: optInstantCodec

    private def rowToMessage(t: Long *: Long *: String *: Boolean *: Int *: Instant *: Instant *: Option[Instant] *: EmptyTuple): MessageData =
      t match {
        case id *: author *: content *: deleted *: ver *: created *: updated *: clientEdited *: EmptyTuple =>
          MessageData(id, author, content, deleted, ver, created, updated, clientEdited)
      }

    // ---- queries & commands --------------------------------------------------

    private val createMessageQuery: Query[(Long, String, Option[Instant]), MessageData] =
      sql"""INSERT INTO messages(author_user_id, content, client_edited_at)
            VALUES ($int8, $text, ${instantCodec.opt})
            RETURNING id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at"""
        .query(messageRowCodec)
        .map(rowToMessage)

    private val findMessageQuery: Query[Long, MessageData] =
      sql"""SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
            FROM messages WHERE id = $int8"""
        .query(messageRowCodec)
        .map(rowToMessage)

    private val searchMessagesQuery: Query[Long *: String *: Option[Long] *: Option[Long] *: Int *: EmptyTuple, MessageData] =
      sql"""SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
            FROM messages
            WHERE author_user_id = $int8 AND content ILIKE $text AND (${int8.opt} IS NULL OR id < ${int8.opt})
            ORDER BY id DESC
            LIMIT $int4"""
        .query(messageRowCodec)
        .map(rowToMessage)

    private val insertMessageEditCommand: Command[Long *: Int *: String *: String *: Long *: EmptyTuple] =
      sql"""INSERT INTO message_edits(message_id, version, previous_content, new_content, edited_by_user_id)
        VALUES ($int8, $int4, $text, $text, $int8)""".command

    private val updateMessageQuery: Query[String *: Option[Instant] *: Long *: Option[Int] *: Option[Int] *: EmptyTuple, MessageData] =
      sql"""UPDATE messages
            SET content = $text,
                updated_at = NOW(),
                version = version + 1,
                client_edited_at = COALESCE(${instantCodec.opt}, client_edited_at)
            WHERE id = $int8
              AND (${int4.opt} IS NULL OR version = ${int4.opt})
            RETURNING id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at"""
        .query(messageRowCodec)
        .map(rowToMessage)

    private val deleteMessageCommand: Command[Long] =
      sql"UPDATE messages SET deleted = TRUE, updated_at = NOW() WHERE id = $int8".command

    private val messageHistoryQuery: Query[Long, MessageHistoryData] =
      sql"""SELECT version, previous_content, new_content, edited_by_user_id, edited_at
            FROM message_edits WHERE message_id = $int8 ORDER BY version ASC"""
        .query(int4 *: text *: text *: int8 *: instantCodec)
        .map { case (ver, prev, next, editor, ts) => MessageHistoryData(ver, prev, next, editor, ts) }

    private val isAdminQuery: Query[Long, Boolean] =
      sql"SELECT is_admin FROM users WHERE id = $int8".query(bool)

    private val insertAuditCommand: Command[Long *: String *: Option[Long] *: Option[Long] *: Option[String] *: EmptyTuple] =
      sql"""INSERT INTO audit_log(actor_user_id, action, target_user_id, message_id, details)
        VALUES ($int8, $varchar, ${int8.opt}, ${int8.opt}, ${text.opt})""".command

    private val listAuditQuery: Query[Option[Long] *: Option[Long] *: Option[Long] *: Option[Long] *: Option[Long] *: Option[Long] *: Int *: EmptyTuple, AuditEntry] =
      sql"""SELECT id, actor_user_id, action::text, target_user_id, message_id, details, created_at
            FROM audit_log
            WHERE (${int8.opt} IS NULL OR target_user_id = ${int8.opt})
              AND (${int8.opt} IS NULL OR message_id = ${int8.opt})
              AND (${int8.opt} IS NULL OR id < ${int8.opt})
            ORDER BY id DESC
            LIMIT $int4"""
        .query(int8 *: int8 *: text *: int8.opt *: int8.opt *: text.opt *: instantCodec)
        .map { case (id, actor, action, target, msg, details, ts) => AuditEntry(id, actor, action, target, msg, details, ts) }

    private val maxEditVersionQuery: Query[Long, Int] =
      sql"SELECT COALESCE(MAX(version), 0) + 1 FROM message_edits WHERE message_id = $int8".query(int4)

    // ---- helpers -------------------------------------------------------------

    private def withSkunkOrJdbc[A](skunkEffect: Task[A])(jdbcEffect: => Task[A]): Task[A] =
      skunkEffect.catchSome {
        case ex: RuntimeException if ex.getMessage == "Skunk runtime is disabled" => jdbcEffect
      }

    private def isAdminSkunk(session: Session[Task], userId: Long): Task[Boolean] =
      session.option(isAdminQuery)(userId).map(_.getOrElse(false))

    private def writeAuditSkunk(session: Session[Task], actorUserId: Long, action: String, targetUserId: Option[Long], messageId: Option[Long], details: Option[String]): Task[Unit] =
      session.execute(insertAuditCommand)(actorUserId *: action *: targetUserId *: messageId *: details *: EmptyTuple).unit

    private def authorizeReadOrWrite(requesterUserId: Long, ownerUserId: Long, requesterIsAdmin: Boolean): Task[Unit] =
      if requesterUserId == ownerUserId || requesterIsAdmin then ZIO.unit
      else ZIO.fail(new RuntimeException("Not allowed"))

    private def requireAdmin(userId: Long): Task[Unit] =
      for {
        admin <- isAdmin(userId)
        _     <- ZIO.fail(new RuntimeException("Admin rights are required")).unless(admin)
      } yield ()

    private def isAdmin(userId: Long): Task[Boolean] =
      withSkunkOrJdbc {
        skunkPool.withSession(s => isAdminSkunk(s, userId))
      } {
        db.withConnection { connection =>
          val statement = connection.prepareStatement("SELECT is_admin FROM users WHERE id = ?")
          try {
            statement.setLong(1, userId)
            val rs = statement.executeQuery()
            if !rs.next() then { rs.close(); false }
            else { val v = rs.getBoolean("is_admin"); rs.close(); v }
          } finally {
            statement.close()
          }
        }
      }

    private def findMessage(messageId: Long): Task[MessageData] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.option(findMessageQuery)(messageId)).flatMap {
          case Some(m) => ZIO.succeed(m)
          case None    => ZIO.fail(new RuntimeException("Message not found"))
        }
      } {
        db.withConnection { connection =>
          val statement = connection.prepareStatement(
            "SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at FROM messages WHERE id = ?"
          )
          try {
            statement.setLong(1, messageId)
            val rs = statement.executeQuery()
            if !rs.next() then { rs.close(); throw new RuntimeException("Message not found") }
            val message = readMessageRow(rs)
            rs.close()
            message
          } finally {
            statement.close()
          }
        }
      }

    private def writeAudit(actorUserId: Long, action: String, targetUserId: Option[Long], messageId: Option[Long], details: Option[String]): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession(writeAuditSkunk(_, actorUserId, action, targetUserId, messageId, details))
      } {
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
            targetUserId match { case Some(id) => statement.setLong(3, id); case None => statement.setNull(3, java.sql.Types.BIGINT) }
            messageId match { case Some(id) => statement.setLong(4, id); case None => statement.setNull(4, java.sql.Types.BIGINT) }
            details match { case Some(v) => statement.setString(5, v); case None => statement.setNull(5, java.sql.Types.VARCHAR) }
            statement.executeUpdate()
            ()
          } finally {
            statement.close()
          }
        }
      }

    // ---- public API ----------------------------------------------------------

    override def createMessage(userId: Long, content: String, clientEditedAtEpochMillis: Option[Long]): Task[MessageData] = {
      val trimmed = content.trim
      if trimmed.isEmpty then ZIO.fail(new RuntimeException("Message content cannot be empty"))
      else {
        val clientInstant = clientEditedAtEpochMillis.map(Instant.ofEpochMilli)
        withSkunkOrJdbc {
          skunkPool.withSession(_.unique(createMessageQuery)((userId, trimmed, clientInstant)))
        } {
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
                case None        => statement.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
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
    }

    override def searchMessages(
      requesterUserId: Long,
      query: String,
      targetUserId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[MessageData]] = {
      val searchText = query.trim
      if searchText.isEmpty then ZIO.succeed(CursorPage(Nil, None))
      else {
        for {
          parsedCursor       <- parseCursor(cursor)
          validatedPageSize  <- validatePageSize(pageSize)
          isRequesterAdmin   <- isAdmin(requesterUserId)
          _ <- targetUserId match {
            case Some(target) if target != requesterUserId && !isRequesterAdmin =>
              ZIO.fail(new RuntimeException("Admin rights are required to search other users' messages"))
            case _ => ZIO.unit
          }
          searchUserId = targetUserId.getOrElse(requesterUserId)
          page <- withSkunkOrJdbc {
            val fetchSize = validatedPageSize + 1
            skunkPool.withSession(_.execute(searchMessagesQuery)(searchUserId *: s"%$searchText%" *: parsedCursor *: parsedCursor *: fetchSize *: EmptyTuple))
              .map(rows => paginate(rows, validatedPageSize, _.id))
          } {
            db.withConnection { connection =>
              val fetchSize = validatedPageSize + 1
              val sql =
                """
                  |SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at
                  |FROM messages
                  |WHERE author_user_id = ? AND content ILIKE ? AND (? IS NULL OR id < ?)
                  |ORDER BY id DESC
                  |LIMIT ?
                  |""".stripMargin
              val statement = connection.prepareStatement(sql)
              try {
                statement.setLong(1, searchUserId)
                statement.setString(2, s"%$searchText%")
                parsedCursor match {
                  case Some(value) =>
                    statement.setLong(3, value)
                    statement.setLong(4, value)
                  case None =>
                    statement.setNull(3, java.sql.Types.BIGINT)
                    statement.setNull(4, java.sql.Types.BIGINT)
                }
                statement.setInt(5, fetchSize)
                val rs = statement.executeQuery()
                val items = List.newBuilder[MessageData]
                while rs.next() do items += readMessageRow(rs)
                rs.close()
                paginate(items.result(), validatedPageSize, _.id)
              } finally {
                statement.close()
              }
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
        } yield page
      }
    }

    override def getMessageById(requesterUserId: Long, messageId: Long): Task[MessageData] = {
      for {
        message          <- findMessage(messageId)
        isRequesterAdmin <- isAdmin(requesterUserId)
        _                <- authorizeReadOrWrite(requesterUserId, message.authorUserId, isRequesterAdmin)
        _ <- if isRequesterAdmin && requesterUserId != message.authorUserId then
          writeAudit(requesterUserId, "admin.read.message", Some(message.authorUserId), Some(message.id), None)
        else ZIO.unit
      } yield message
    }

    override def editMessage(
      editorUserId: Long,
      messageId: Long,
      newContent: String,
      expectedVersion: Option[Int],
      clientEditedAtEpochMillis: Option[Long]
    ): Task[MessageData] = {
      val trimmed = newContent.trim
      if trimmed.isEmpty then ZIO.fail(new RuntimeException("Message content cannot be empty"))
      else {
        for {
          isEditorAdmin <- isAdmin(editorUserId)
          result <- withSkunkOrJdbc {
            skunkPool.withSession { session =>
              session.transaction.use { _ =>
                for {
                  current <- session.option(findMessageQuery)(messageId).flatMap {
                    case None    => ZIO.fail(new RuntimeException("Message not found"))
                    case Some(m) => ZIO.succeed(m)
                  }
                  _ <- ZIO.fail(new RuntimeException("Deleted messages cannot be edited")).when(current.deleted)
                  _ <- ZIO.fail(new RuntimeException("Only the author or an admin can edit the message"))
                        .unless(current.authorUserId == editorUserId || isEditorAdmin)
                  _ <- ZIO.fail(new RuntimeException(
                        s"Optimistic concurrency conflict: expected version ${expectedVersion.getOrElse(-1)} but found ${current.version}"
                       )).unless(OptimisticConcurrency.matches(expectedVersion, current.version))
                  nextVer <- session.unique(maxEditVersionQuery)(messageId)
                  _ <- session.execute(insertMessageEditCommand)(messageId *: nextVer *: current.content *: trimmed *: editorUserId *: EmptyTuple)
                  clientInstant = clientEditedAtEpochMillis.map(Instant.ofEpochMilli)
                  updated <- session.option(updateMessageQuery)(trimmed *: clientInstant *: messageId *: expectedVersion *: expectedVersion *: EmptyTuple).flatMap {
                    case None    => ZIO.fail(new RuntimeException("Optimistic concurrency conflict: message version changed"))
                    case Some(m) => ZIO.succeed(m)
                  }
                } yield (updated, current.authorUserId)
              }
            }
          } {
            db.withConnection { connection =>
              val fetch = connection.prepareStatement(
                "SELECT id, author_user_id, content, deleted, version, created_at, updated_at, client_edited_at FROM messages WHERE id = ?"
              )
              try {
                fetch.setLong(1, messageId)
                val rs = fetch.executeQuery()
                if !rs.next() then { rs.close(); throw new RuntimeException("Message not found") }
                val current = readMessageRow(rs)
                rs.close()

                if current.deleted then throw new RuntimeException("Deleted messages cannot be edited")
                if current.authorUserId != editorUserId && !isEditorAdmin then
                  throw new RuntimeException("Only the author or an admin can edit the message")
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
                    case None        => update.setNull(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                  }
                  update.setLong(3, messageId)
                  expectedVersion match {
                    case Some(value) => update.setInt(4, value); update.setInt(5, value)
                    case None        => update.setNull(4, java.sql.Types.INTEGER); update.setNull(5, java.sql.Types.INTEGER)
                  }
                  val updatedRs = update.executeQuery()
                  if !updatedRs.next() then { updatedRs.close(); throw new RuntimeException("Optimistic concurrency conflict: message version changed") }
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
          }
          _ <- if isEditorAdmin && editorUserId != result._2 then
            writeAudit(editorUserId, "admin.write.edit_message", Some(result._2), Some(messageId), None)
          else ZIO.unit
        } yield result._1
      }
    }

    override def messageHistory(requesterUserId: Long, messageId: Long): Task[List[MessageHistoryData]] = {
      for {
        message          <- findMessage(messageId)
        isRequesterAdmin <- isAdmin(requesterUserId)
        _                <- authorizeReadOrWrite(requesterUserId, message.authorUserId, isRequesterAdmin)
        history <- withSkunkOrJdbc {
          skunkPool.withSession(_.execute(messageHistoryQuery)(messageId))
        } {
          db.withConnection { connection =>
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
        }
        _ <- if isRequesterAdmin && requesterUserId != message.authorUserId then
          writeAudit(requesterUserId, "admin.read.message_history", Some(message.authorUserId), Some(messageId), None)
        else ZIO.unit
      } yield history
    }

    override def deleteMessage(actorUserId: Long, messageId: Long): Task[Unit] = {
      for {
        message      <- findMessage(messageId)
        isActorAdmin <- isAdmin(actorUserId)
        _            <- authorizeReadOrWrite(actorUserId, message.authorUserId, isActorAdmin)
        _ <- withSkunkOrJdbc {
          skunkPool.withSession(_.execute(deleteMessageCommand)(messageId).unit)
        } {
          db.withConnection { connection =>
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
        }
        _ <- if isActorAdmin && actorUserId != message.authorUserId then
          writeAudit(actorUserId, "admin.write.delete_message", Some(message.authorUserId), Some(messageId), None)
        else ZIO.unit
      } yield ()
    }

    override def listAuditEntries(
      requesterUserId: Long,
      targetUserId: Option[Long],
      messageId: Option[Long],
      cursor: Option[String],
      pageSize: Int
    ): Task[CursorPage[AuditEntry]] = {
      for {
        parsedCursor      <- parseCursor(cursor)
        validatedPageSize <- validatePageSize(pageSize)
        _                 <- requireAdmin(requesterUserId)
        page <- withSkunkOrJdbc {
          val fetchSize = validatedPageSize + 1
          skunkPool.withSession(_.execute(listAuditQuery)(targetUserId *: targetUserId *: messageId *: messageId *: parsedCursor *: parsedCursor *: fetchSize *: EmptyTuple))
            .map(rows => paginate(rows, validatedPageSize, _.id))
        } {
          db.withConnection { connection =>
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
                      case Some(value) => ps.setLong(3, value); ps.setLong(4, value)
                      case None        => ps.setNull(3, java.sql.Types.BIGINT); ps.setNull(4, java.sql.Types.BIGINT)
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
                      case Some(value) => ps.setLong(2, value); ps.setLong(3, value)
                      case None        => ps.setNull(2, java.sql.Types.BIGINT); ps.setNull(3, java.sql.Types.BIGINT)
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
                      case Some(value) => ps.setLong(2, value); ps.setLong(3, value)
                      case None        => ps.setNull(2, java.sql.Types.BIGINT); ps.setNull(3, java.sql.Types.BIGINT)
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
                      case Some(value) => ps.setLong(1, value); ps.setLong(2, value)
                      case None        => ps.setNull(1, java.sql.Types.BIGINT); ps.setNull(2, java.sql.Types.BIGINT)
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
        }
      } yield page
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
  }

  val layer: URLayer[Database & SkunkSessionPool, MessagingService] = ZLayer {
    for {
      db        <- ZIO.service[Database]
      skunkPool <- ZIO.service[SkunkSessionPool]
    } yield new LiveMessagingService(db, skunkPool)
  }
}