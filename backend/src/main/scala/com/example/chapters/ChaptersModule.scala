package com.example.chapters

import zio.*
import com.example.infrastructure.db.Database
import com.example.messaging.MessagingModule.MessageData
import java.security.SecureRandom
import java.util.Base64

object ChaptersModule {

  private val shareLinkTokenPattern = "^[A-Za-z0-9_-]{20,100}$".r

  final case class Chapter(
    id: Long,
    ownerUserId: Long,
    title: String,
    parentChapterId: Option[Long],
    visibility: String,
    createdAtEpochMillis: Long
  )

  final case class ChapterMember(userId: Long, role: String)

  final case class ChapterDetail(
    chapter: Chapter,
    members: List[ChapterMember],
    messageIds: List[Long]
  )

  final case class ChapterPreference(
    chapterId: Long,
    isImportant: Boolean,
    muteLevel: String,
    updatedAtEpochMillis: Long
  )

  final case class ChapterUnreadState(chapterId: Long, unreadCount: Int, muteLevel: String)
  final case class ChapterMessagesPage(items: List[MessageData], nextCursor: Option[String])

  final case class ShareLinkTarget(targetType: String, chapterId: Option[Long], messageId: Option[Long])

  trait ChaptersService {
    def createChapter(ownerUserId: Long, title: String, parentChapterId: Option[Long]): Task[Chapter]
    def listAccessibleChapters(userId: Long): Task[List[Chapter]]
    def getChapterDetail(userId: Long, chapterId: Long): Task[ChapterDetail]
    def listChapterMessages(userId: Long, chapterId: Long, cursor: Option[String], pageSize: Int): Task[ChapterMessagesPage]
    def updateVisibility(ownerUserId: Long, chapterId: Long, visibility: String): Task[Unit]
    def addMember(ownerUserId: Long, chapterId: Long, targetUserId: Long, role: String): Task[Unit]
    def removeMember(ownerUserId: Long, chapterId: Long, targetUserId: Long): Task[Unit]
    def addMessageToChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit]
    def removeMessageFromChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit]
    def upsertPreference(userId: Long, chapterId: Long, isImportant: Boolean, muteLevel: String): Task[ChapterPreference]
    def getPreference(userId: Long, chapterId: Long): Task[ChapterPreference]
    def listPreferences(userId: Long): Task[List[ChapterPreference]]
    def markMessageRead(userId: Long, chapterId: Long, messageId: Long): Task[Unit]
    def markUnreadFrom(userId: Long, chapterId: Long, messageId: Long): Task[Unit]
    def unreadCount(userId: Long, chapterId: Long): Task[ChapterUnreadState]
    def deleteChapter(ownerUserId: Long, chapterId: Long): Task[Unit]
    def createShareLink(ownerUserId: Long, chapterId: Option[Long], messageId: Option[Long]): Task[String]
    def resolveShareLink(token: String): Task[ShareLinkTarget]
  }

  final class LiveChaptersService(db: Database) extends ChaptersService {

    private val rng = new SecureRandom()
    private val shareLinkTtlDays = 7

    private def generateToken(): String = {
      val bytes = new Array[Byte](24)
      rng.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

    private def requireOwnerOrEditor(connection: java.sql.Connection, userId: Long, chapterId: Long): Unit = {
      val stmt = connection.prepareStatement(
        """SELECT c.owner_user_id, cm.role
           FROM chapters c
           LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = ?
           WHERE c.id = ?"""
      )
      try {
        stmt.setLong(1, userId)
        stmt.setLong(2, chapterId)
        val rs = stmt.executeQuery()
        if !rs.next() then throw new RuntimeException("Chapter not found")
        val ownerUserId = rs.getLong("owner_user_id")
        val memberRole = Option(rs.getString("role"))
        rs.close()
        val isOwner = ownerUserId == userId
        val isEditor = memberRole.contains("editor")
        if !isOwner && !isEditor then throw new RuntimeException("Not allowed: owner or editor role required")
      } finally {
        stmt.close()
      }
    }

    private def requireOwner(connection: java.sql.Connection, userId: Long, chapterId: Long): Unit = {
      val stmt = connection.prepareStatement("SELECT owner_user_id FROM chapters WHERE id = ?")
      try {
        stmt.setLong(1, chapterId)
        val rs = stmt.executeQuery()
        if !rs.next() then throw new RuntimeException("Chapter not found")
        val ownerUserId = rs.getLong("owner_user_id")
        rs.close()
        if ownerUserId != userId then throw new RuntimeException("Not allowed: only the chapter owner can perform this action")
      } finally {
        stmt.close()
      }
    }

    private def requireReadAccess(connection: java.sql.Connection, userId: Long, chapterId: Long): Unit = {
      val stmt = connection.prepareStatement(
        """SELECT c.owner_user_id, c.visibility,
              cm.user_id AS member_user_id,
              EXISTS (
                SELECT 1 FROM chapter_group_access cga
                JOIN group_members gm ON gm.group_id = cga.group_id
                WHERE cga.chapter_id = c.id AND gm.user_id = ?
              ) AS in_group
           FROM chapters c
           LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = ?
           WHERE c.id = ?"""
      )
      try {
        stmt.setLong(1, userId)
        stmt.setLong(2, userId)
        stmt.setLong(3, chapterId)
        val rs = stmt.executeQuery()
        if !rs.next() then throw new RuntimeException("Chapter not found")
        val ownerUserId = rs.getLong("owner_user_id")
        val visibility = rs.getString("visibility")
        val memberUserId = rs.getLong("member_user_id")
        val isMember = !rs.wasNull() && memberUserId == userId
        val inGroup = rs.getBoolean("in_group")
        rs.close()
        val hasAccess =
          ownerUserId == userId ||
            visibility == "public" ||
            visibility == "authenticated" ||
            ((visibility == "individuals" || visibility == "members") && isMember) ||
            (visibility == "group" && inGroup)
        if !hasAccess then throw new RuntimeException("Not allowed: no read access to this chapter")
      } finally {
        stmt.close()
      }
    }

    private def requireMessageInChapter(connection: java.sql.Connection, chapterId: Long, messageId: Long): Unit = {
      val stmt = connection.prepareStatement(
        "SELECT 1 FROM chapter_messages WHERE chapter_id = ? AND message_id = ?"
      )
      try {
        stmt.setLong(1, chapterId)
        stmt.setLong(2, messageId)
        val rs = stmt.executeQuery()
        val exists = rs.next()
        rs.close()
        if !exists then throw new RuntimeException("Message does not belong to chapter")
      } finally {
        stmt.close()
      }
    }

    private def validateMuteLevel(muteLevel: String): Task[String] = {
      val normalized = muteLevel.trim.toLowerCase
      val allowed = Set("none", "soft", "hard")
      if allowed.contains(normalized) then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("muteLevel must be one of: none, soft, hard"))
    }

    private def readPreferenceMuteLevel(connection: java.sql.Connection, userId: Long, chapterId: Long): String = {
      val stmt = connection.prepareStatement(
        "SELECT mute_level FROM chapter_user_preferences WHERE chapter_id = ? AND user_id = ?"
      )
      try {
        stmt.setLong(1, chapterId)
        stmt.setLong(2, userId)
        val rs = stmt.executeQuery()
        val level = if rs.next() then rs.getString("mute_level") else "none"
        rs.close()
        level
      } finally {
        stmt.close()
      }
    }

    override def createChapter(ownerUserId: Long, title: String, parentChapterId: Option[Long]): Task[Chapter] = {
      db.withConnection { connection =>
        val stmt = connection.prepareStatement(
          "INSERT INTO chapters(owner_user_id, title, parent_chapter_id) VALUES (?, ?, ?) RETURNING id, created_at"
        )
        try {
          stmt.setLong(1, ownerUserId)
          stmt.setString(2, title)
          parentChapterId match {
            case Some(pid) => stmt.setLong(3, pid)
            case None      => stmt.setNull(3, java.sql.Types.BIGINT)
          }
          val rs = stmt.executeQuery()
          rs.next()
          val id = rs.getLong("id")
          val createdAt = rs.getTimestamp("created_at").toInstant.toEpochMilli
          rs.close()
          Chapter(id, ownerUserId, title, parentChapterId, "private", createdAt)
        } finally {
          stmt.close()
        }
      }
    }

    override def listAccessibleChapters(userId: Long): Task[List[Chapter]] = {
      db.withConnection { connection =>
        val stmt = connection.prepareStatement(
          """SELECT c.id, c.owner_user_id, c.title, c.parent_chapter_id, c.visibility, c.created_at
             FROM chapters c
             WHERE c.owner_user_id = ?
                OR c.visibility = 'public'
                OR c.visibility = 'authenticated'
                OR EXISTS (
                  SELECT 1 FROM chapter_members cm
                  WHERE cm.chapter_id = c.id AND cm.user_id = ? AND c.visibility IN ('individuals', 'members')
                )
                OR EXISTS (
                  SELECT 1 FROM chapter_group_access cga
                  JOIN group_members gm ON gm.group_id = cga.group_id
                  WHERE cga.chapter_id = c.id AND gm.user_id = ? AND c.visibility = 'group'
                )
             ORDER BY c.created_at DESC"""
        )
        try {
          stmt.setLong(1, userId)
          stmt.setLong(2, userId)
          stmt.setLong(3, userId)
          val rs = stmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[Chapter]
          while rs.next() do {
            val parentId = rs.getLong("parent_chapter_id")
            buf += Chapter(
              id = rs.getLong("id"),
              ownerUserId = rs.getLong("owner_user_id"),
              title = rs.getString("title"),
              parentChapterId = if rs.wasNull() then None else Some(parentId),
              visibility = rs.getString("visibility"),
              createdAtEpochMillis = rs.getTimestamp("created_at").toInstant.toEpochMilli
            )
          }
          rs.close()
          buf.toList
        } finally {
          stmt.close()
        }
      }
    }

    override def getChapterDetail(userId: Long, chapterId: Long): Task[ChapterDetail] = {
      db.withConnection { connection =>
        requireReadAccess(connection, userId, chapterId)

        val chapterStmt = connection.prepareStatement(
          "SELECT id, owner_user_id, title, parent_chapter_id, visibility, created_at FROM chapters WHERE id = ?"
        )
        val chapter = try {
          chapterStmt.setLong(1, chapterId)
          val rs = chapterStmt.executeQuery()
          rs.next()
          val parentId = rs.getLong("parent_chapter_id")
          val c = Chapter(
            id = rs.getLong("id"),
            ownerUserId = rs.getLong("owner_user_id"),
            title = rs.getString("title"),
            parentChapterId = if rs.wasNull() then None else Some(parentId),
            visibility = rs.getString("visibility"),
            createdAtEpochMillis = rs.getTimestamp("created_at").toInstant.toEpochMilli
          )
          rs.close()
          c
        } finally {
          chapterStmt.close()
        }

        val membersStmt = connection.prepareStatement(
          "SELECT user_id, role FROM chapter_members WHERE chapter_id = ? ORDER BY invited_at"
        )
        val members = try {
          membersStmt.setLong(1, chapterId)
          val rs = membersStmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[ChapterMember]
          while rs.next() do buf += ChapterMember(rs.getLong("user_id"), rs.getString("role"))
          rs.close()
          buf.toList
        } finally {
          membersStmt.close()
        }

        val msgsStmt = connection.prepareStatement(
          "SELECT message_id FROM chapter_messages WHERE chapter_id = ? ORDER BY added_at DESC"
        )
        val messageIds = try {
          msgsStmt.setLong(1, chapterId)
          val rs = msgsStmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[Long]
          while rs.next() do buf += rs.getLong("message_id")
          rs.close()
          buf.toList
        } finally {
          msgsStmt.close()
        }

        ChapterDetail(chapter, members, messageIds)
      }
    }

    override def listChapterMessages(userId: Long, chapterId: Long, cursor: Option[String], pageSize: Int): Task[ChapterMessagesPage] = {
      for {
        parsedCursor <- parseCursor(cursor)
        validatedPageSize <- validatePageSize(pageSize)
        page <- db.withConnection { connection =>
          requireReadAccess(connection, userId, chapterId)

          val fetchSize = validatedPageSize + 1
          val (sql, bind) = parsedCursor match {
            case Some(value) =>
              (
                 """SELECT m.id, m.author_user_id, m.content, m.deleted, m.version, m.created_at, m.updated_at, m.client_edited_at
                   FROM chapter_messages cm
                   JOIN messages m ON m.id = cm.message_id
                   WHERE cm.chapter_id = ? AND cm.message_id < ?
                   ORDER BY cm.message_id DESC
                   LIMIT ?""",
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, chapterId)
                  ps.setLong(2, value)
                  ps.setInt(3, fetchSize)
                }
              )
            case None =>
              (
                 """SELECT m.id, m.author_user_id, m.content, m.deleted, m.version, m.created_at, m.updated_at, m.client_edited_at
                   FROM chapter_messages cm
                   JOIN messages m ON m.id = cm.message_id
                   WHERE cm.chapter_id = ?
                   ORDER BY cm.message_id DESC
                   LIMIT ?""",
                (ps: java.sql.PreparedStatement) => {
                  ps.setLong(1, chapterId)
                  ps.setInt(2, fetchSize)
                }
              )
          }

          val stmt = connection.prepareStatement(sql)
          try {
            bind(stmt)
            val rs = stmt.executeQuery()
            val buf = List.newBuilder[MessageData]
            while rs.next() do {
              buf += MessageData(
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
            rs.close()
            paginateMessages(buf.result(), validatedPageSize)
          } finally {
            stmt.close()
          }
        }
      } yield page
    }

    override def updateVisibility(ownerUserId: Long, chapterId: Long, visibility: String): Task[Unit] = {
      val normalized = if visibility == "members" then "individuals" else visibility
      val allowed = Set("private", "individuals", "authenticated", "group", "public")
      if !allowed.contains(normalized) then
        ZIO.fail(new RuntimeException(s"Invalid visibility '$visibility': must be one of: ${allowed.mkString(", ")}"))
      else
        db.withConnection { connection =>
          requireOwner(connection, ownerUserId, chapterId)
          val stmt = connection.prepareStatement("UPDATE chapters SET visibility = ?, updated_at = NOW() WHERE id = ?")
          try {
            stmt.setString(1, normalized)
            stmt.setLong(2, chapterId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
    }

    override def addMember(ownerUserId: Long, chapterId: Long, targetUserId: Long, role: String): Task[Unit] = {
      val allowed = Set("viewer", "editor")
      if !allowed.contains(role) then
        ZIO.fail(new RuntimeException(s"Invalid role '$role': must be viewer or editor"))
      else
        db.withConnection { connection =>
          requireOwner(connection, ownerUserId, chapterId)
          val stmt = connection.prepareStatement(
            """INSERT INTO chapter_members(chapter_id, user_id, role) VALUES (?, ?, ?)
               ON CONFLICT (chapter_id, user_id) DO UPDATE SET role = EXCLUDED.role"""
          )
          try {
            stmt.setLong(1, chapterId)
            stmt.setLong(2, targetUserId)
            stmt.setString(3, role)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
    }

    override def removeMember(ownerUserId: Long, chapterId: Long, targetUserId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireOwner(connection, ownerUserId, chapterId)
        val stmt = connection.prepareStatement(
          "DELETE FROM chapter_members WHERE chapter_id = ? AND user_id = ?"
        )
        try {
          stmt.setLong(1, chapterId)
          stmt.setLong(2, targetUserId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def addMessageToChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireOwnerOrEditor(connection, userId, chapterId)
        val stmt = connection.prepareStatement(
          """INSERT INTO chapter_messages(chapter_id, message_id, added_by_user_id) VALUES (?, ?, ?)
             ON CONFLICT (message_id) DO UPDATE
             SET chapter_id = EXCLUDED.chapter_id,
                 added_by_user_id = EXCLUDED.added_by_user_id,
                 added_at = NOW()"""
        )
        try {
          stmt.setLong(1, chapterId)
          stmt.setLong(2, messageId)
          stmt.setLong(3, userId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def removeMessageFromChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireOwnerOrEditor(connection, userId, chapterId)
        val stmt = connection.prepareStatement(
          "DELETE FROM chapter_messages WHERE chapter_id = ? AND message_id = ?"
        )
        try {
          stmt.setLong(1, chapterId)
          stmt.setLong(2, messageId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def upsertPreference(userId: Long, chapterId: Long, isImportant: Boolean, muteLevel: String): Task[ChapterPreference] = {
      for {
        normalizedMute <- validateMuteLevel(muteLevel)
        pref <- db.withConnection { connection =>
          requireReadAccess(connection, userId, chapterId)
          val stmt = connection.prepareStatement(
            """INSERT INTO chapter_user_preferences(chapter_id, user_id, is_important, mute_level, updated_at)
               VALUES (?, ?, ?, ?, NOW())
               ON CONFLICT (chapter_id, user_id)
               DO UPDATE SET is_important = EXCLUDED.is_important, mute_level = EXCLUDED.mute_level, updated_at = NOW()
               RETURNING chapter_id, is_important, mute_level, updated_at"""
          )
          try {
            stmt.setLong(1, chapterId)
            stmt.setLong(2, userId)
            stmt.setBoolean(3, isImportant)
            stmt.setString(4, normalizedMute)
            val rs = stmt.executeQuery()
            rs.next()
            val result = ChapterPreference(
              chapterId = rs.getLong("chapter_id"),
              isImportant = rs.getBoolean("is_important"),
              muteLevel = rs.getString("mute_level"),
              updatedAtEpochMillis = rs.getTimestamp("updated_at").toInstant.toEpochMilli
            )
            rs.close()
            result
          } finally {
            stmt.close()
          }
        }
      } yield pref
    }

    override def getPreference(userId: Long, chapterId: Long): Task[ChapterPreference] = {
      db.withConnection { connection =>
        requireReadAccess(connection, userId, chapterId)
        val stmt = connection.prepareStatement(
          "SELECT chapter_id, is_important, mute_level, updated_at FROM chapter_user_preferences WHERE chapter_id = ? AND user_id = ?"
        )
        try {
          stmt.setLong(1, chapterId)
          stmt.setLong(2, userId)
          val rs = stmt.executeQuery()
          val pref = if rs.next() then {
            ChapterPreference(
              chapterId = rs.getLong("chapter_id"),
              isImportant = rs.getBoolean("is_important"),
              muteLevel = rs.getString("mute_level"),
              updatedAtEpochMillis = rs.getTimestamp("updated_at").toInstant.toEpochMilli
            )
          } else {
            ChapterPreference(chapterId = chapterId, isImportant = false, muteLevel = "none", updatedAtEpochMillis = 0L)
          }
          rs.close()
          pref
        } finally {
          stmt.close()
        }
      }
    }

    override def listPreferences(userId: Long): Task[List[ChapterPreference]] = {
      db.withConnection { connection =>
        val stmt = connection.prepareStatement(
          """SELECT p.chapter_id, p.is_important, p.mute_level, p.updated_at
             FROM chapter_user_preferences p
             JOIN chapters c ON c.id = p.chapter_id
             LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = ?
             WHERE p.user_id = ?
               AND (
                 c.owner_user_id = ?
                 OR c.visibility = 'public'
                 OR c.visibility = 'authenticated'
                 OR (c.visibility IN ('individuals', 'members') AND cm.user_id IS NOT NULL)
                 OR (
                   c.visibility = 'group' AND EXISTS (
                     SELECT 1
                     FROM chapter_group_access cga
                     JOIN group_members gm ON gm.group_id = cga.group_id
                     WHERE cga.chapter_id = c.id AND gm.user_id = ?
                   )
                 )
               )
             ORDER BY p.updated_at DESC"""
        )
        try {
          stmt.setLong(1, userId)
          stmt.setLong(2, userId)
          stmt.setLong(3, userId)
          stmt.setLong(4, userId)
          val rs = stmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[ChapterPreference]
          while rs.next() do {
            buf += ChapterPreference(
              chapterId = rs.getLong("chapter_id"),
              isImportant = rs.getBoolean("is_important"),
              muteLevel = rs.getString("mute_level"),
              updatedAtEpochMillis = rs.getTimestamp("updated_at").toInstant.toEpochMilli
            )
          }
          rs.close()
          buf.toList
        } finally {
          stmt.close()
        }
      }
    }

    override def markMessageRead(userId: Long, chapterId: Long, messageId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireReadAccess(connection, userId, chapterId)
        requireMessageInChapter(connection, chapterId, messageId)
        val stmt = connection.prepareStatement(
          """INSERT INTO message_reads(message_id, chapter_id, user_id, read_at)
             VALUES (?, ?, ?, NOW())
             ON CONFLICT (message_id, user_id)
             DO UPDATE SET chapter_id = EXCLUDED.chapter_id, read_at = NOW()"""
        )
        try {
          stmt.setLong(1, messageId)
          stmt.setLong(2, chapterId)
          stmt.setLong(3, userId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def markUnreadFrom(userId: Long, chapterId: Long, messageId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireReadAccess(connection, userId, chapterId)
        requireMessageInChapter(connection, chapterId, messageId)
        val stmt = connection.prepareStatement(
          """DELETE FROM message_reads mr
             USING chapter_messages cm
             WHERE mr.message_id = cm.message_id
               AND mr.user_id = ?
               AND cm.chapter_id = ?
               AND cm.message_id >= ?"""
        )
        try {
          stmt.setLong(1, userId)
          stmt.setLong(2, chapterId)
          stmt.setLong(3, messageId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def unreadCount(userId: Long, chapterId: Long): Task[ChapterUnreadState] = {
      db.withConnection { connection =>
        requireReadAccess(connection, userId, chapterId)
        val muteLevel = readPreferenceMuteLevel(connection, userId, chapterId)
        if muteLevel == "hard" then {
          ChapterUnreadState(chapterId, 0, muteLevel)
        } else {
          val stmt = connection.prepareStatement(
            """SELECT COUNT(*) AS unread_count
               FROM chapter_messages cm
               JOIN messages m ON m.id = cm.message_id
               LEFT JOIN message_reads mr ON mr.message_id = cm.message_id AND mr.user_id = ?
               WHERE cm.chapter_id = ?
                 AND m.deleted = FALSE
                 AND mr.message_id IS NULL"""
          )
          try {
            stmt.setLong(1, userId)
            stmt.setLong(2, chapterId)
            val rs = stmt.executeQuery()
            rs.next()
            val count = rs.getLong("unread_count").toInt
            rs.close()
            ChapterUnreadState(chapterId, count, muteLevel)
          } finally {
            stmt.close()
          }
        }
      }
    }

    override def deleteChapter(ownerUserId: Long, chapterId: Long): Task[Unit] = {
      db.withConnection { connection =>
        requireOwner(connection, ownerUserId, chapterId)
        val stmt = connection.prepareStatement("DELETE FROM chapters WHERE id = ?")
        try {
          stmt.setLong(1, chapterId)
          stmt.executeUpdate()
          ()
        } finally {
          stmt.close()
        }
      }
    }

    override def createShareLink(ownerUserId: Long, chapterId: Option[Long], messageId: Option[Long]): Task[String] = {
      if chapterId.isEmpty && messageId.isEmpty then
        ZIO.fail(new RuntimeException("Either chapterId or messageId must be provided"))
      else if chapterId.isDefined && messageId.isDefined then
        ZIO.fail(new RuntimeException("Only one of chapterId or messageId may be provided"))
      else
        db.withConnection { connection =>
          chapterId.foreach { cid =>
            requireOwner(connection, ownerUserId, cid)
          }
          messageId.foreach { mid =>
            val stmt = connection.prepareStatement("SELECT author_user_id FROM messages WHERE id = ?")
            try {
              stmt.setLong(1, mid)
              val rs = stmt.executeQuery()
              if !rs.next() then throw new RuntimeException("Message not found")
              val authorId = rs.getLong("author_user_id")
              rs.close()
              if authorId != ownerUserId then throw new RuntimeException("Not allowed: only the message author can create a share link")
            } finally {
              stmt.close()
            }
          }

          val token = generateToken()
          val stmt = connection.prepareStatement(
            """
              |INSERT INTO share_links(token, owner_user_id, chapter_id, message_id, expires_at)
              |VALUES (?, ?, ?, ?, NOW() + (? * INTERVAL '1 day'))
              |""".stripMargin
          )
          try {
            stmt.setString(1, token)
            stmt.setLong(2, ownerUserId)
            chapterId match {
              case Some(cid) => stmt.setLong(3, cid)
              case None      => stmt.setNull(3, java.sql.Types.BIGINT)
            }
            messageId match {
              case Some(mid) => stmt.setLong(4, mid)
              case None      => stmt.setNull(4, java.sql.Types.BIGINT)
            }
            stmt.setInt(5, shareLinkTtlDays)
            stmt.executeUpdate()
            token
          } finally {
            stmt.close()
          }
        }
    }

    override def resolveShareLink(token: String): Task[ShareLinkTarget] = {
      for {
        normalizedToken <- validateShareLinkToken(token)
        target <- db.withConnection { connection =>
          val stmt = connection.prepareStatement(
            """
              |SELECT chapter_id, message_id
              |FROM share_links
              |WHERE token = ?
              |  AND (expires_at IS NULL OR expires_at > NOW())
              |""".stripMargin
          )
          try {
            stmt.setString(1, normalizedToken)
            val rs = stmt.executeQuery()
            if !rs.next() then throw new RuntimeException("Share link not found or expired")
            val chapterId = rs.getLong("chapter_id")
            val chapIdOpt = if rs.wasNull() then None else Some(chapterId)
            val messageId = rs.getLong("message_id")
            val msgIdOpt = if rs.wasNull() then None else Some(messageId)
            rs.close()
            val targetType = if chapIdOpt.isDefined then "chapter" else "message"
            ShareLinkTarget(targetType, chapIdOpt, msgIdOpt)
          } finally {
            stmt.close()
          }
        }
      } yield {
        target
      }
    }

    private def validateShareLinkToken(token: String): Task[String] = {
      val normalized = token.trim
      if shareLinkTokenPattern.matches(normalized) then ZIO.succeed(normalized)
      else ZIO.fail(new RuntimeException("Share link not found or expired"))
    }

    private def parseCursor(cursor: Option[String]): Task[Option[Long]] = {
      cursor match {
        case None => ZIO.succeed(None)
        case Some(value) =>
          ZIO.attempt(value.trim.toLong).map(Some(_)).mapError(_ => new RuntimeException("Invalid cursor"))
      }
    }

    private def validatePageSize(size: Int): Task[Int] = {
      if size >= 1 && size <= 100 then ZIO.succeed(size)
      else ZIO.fail(new RuntimeException("pageSize must be between 1 and 100"))
    }

    private def paginateMessages(rows: List[MessageData], pageSize: Int): ChapterMessagesPage = {
      if rows.size > pageSize then {
        val items = rows.take(pageSize)
        ChapterMessagesPage(items, items.lastOption.map(_.id.toString))
      } else {
        ChapterMessagesPage(rows, None)
      }
    }
  }

  val layer: URLayer[Database, ChaptersService] = ZLayer {
    ZIO.serviceWith[Database](new LiveChaptersService(_))
  }
}