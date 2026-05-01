package com.example.chapters

import zio.*
import com.example.infrastructure.db.{Database, SkunkSessionPool}
import com.example.messaging.MessagingModule.MessageData
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import java.security.SecureRandom
import java.time.ZoneOffset
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

  final class LiveChaptersService(db: Database, skunkPool: SkunkSessionPool) extends ChaptersService {

    private val rng = new SecureRandom()
    private val shareLinkTtlDays = 7

    private def generateToken(): String = {
      val bytes = new Array[Byte](24)
      rng.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

    private val instantCodec = timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))
    private val optInstantCodec = timestamptz.opt.imap(_.map(_.toInstant))(_.map(_.atOffset(ZoneOffset.UTC)))

    // ---- Skunk queries -------------------------------------------------------

    private val createChapterQuery: Query[(Long, String, Option[Long]), Chapter] =
      sql"""INSERT INTO chapters(owner_user_id, title, parent_chapter_id)
            VALUES ($int8, $varchar, ${int8.opt})
            RETURNING id, owner_user_id, title::text, parent_chapter_id, visibility::text, created_at"""
        .query(int8 *: int8 *: text *: int8.opt *: text *: instantCodec)
        .map { case (id, owner, title, parent, vis, ts) =>
          Chapter(id, owner, title, parent, vis, ts.toEpochMilli)
        }

    private val listAccessibleChaptersQuery: Query[Long *: Long *: Long *: EmptyTuple, Chapter] =
      sql"""SELECT c.id, c.owner_user_id, c.title::text, c.parent_chapter_id, c.visibility::text, c.created_at
            FROM chapters c
            WHERE c.owner_user_id = $int8
               OR c.visibility = 'public'
               OR c.visibility = 'authenticated'
               OR EXISTS (
                 SELECT 1 FROM chapter_members cm
                 WHERE cm.chapter_id = c.id AND cm.user_id = $int8 AND c.visibility IN ('individuals', 'members')
               )
               OR EXISTS (
                 SELECT 1 FROM chapter_group_access cga
                 JOIN group_members gm ON gm.group_id = cga.group_id
                 WHERE cga.chapter_id = c.id AND gm.user_id = $int8 AND c.visibility = 'group'
               )
            ORDER BY c.created_at DESC"""
        .query(int8 *: int8 *: text *: int8.opt *: text *: instantCodec)
        .map { case (id, owner, title, parent, vis, ts) =>
          Chapter(id, owner, title, parent, vis, ts.toEpochMilli)
        }

    private val getChapterRowQuery: Query[Long, Chapter] =
      sql"SELECT id, owner_user_id, title::text, parent_chapter_id, visibility::text, created_at FROM chapters WHERE id = $int8"
        .query(int8 *: int8 *: text *: int8.opt *: text *: instantCodec)
        .map { case (id, owner, title, parent, vis, ts) =>
          Chapter(id, owner, title, parent, vis, ts.toEpochMilli)
        }

    private val getMembersQuery: Query[Long, ChapterMember] =
      sql"SELECT user_id, role::text FROM chapter_members WHERE chapter_id = $int8 ORDER BY invited_at"
        .query(int8 *: text)
        .map { case (uid, role) => ChapterMember(uid, role) }

    private val getMessageIdsQuery: Query[Long, Long] =
      sql"SELECT message_id FROM chapter_messages WHERE chapter_id = $int8 ORDER BY added_at DESC"
        .query(int8)

    private val updateVisibilityCommand: Command[String *: Long *: EmptyTuple] =
      sql"UPDATE chapters SET visibility = $varchar, updated_at = NOW() WHERE id = $int8".command

    private val addMemberCommand: Command[Long *: Long *: String *: EmptyTuple] =
      sql"""INSERT INTO chapter_members(chapter_id, user_id, role) VALUES ($int8, $int8, $varchar)
            ON CONFLICT (chapter_id, user_id) DO UPDATE SET role = EXCLUDED.role""".command

    private val removeMemberCommand: Command[Long *: Long *: EmptyTuple] =
      sql"DELETE FROM chapter_members WHERE chapter_id = $int8 AND user_id = $int8".command

    private val addMessageToChapterCommand: Command[Long *: Long *: Long *: EmptyTuple] =
      sql"""INSERT INTO chapter_messages(chapter_id, message_id, added_by_user_id) VALUES ($int8, $int8, $int8)
            ON CONFLICT (message_id) DO UPDATE
            SET chapter_id = EXCLUDED.chapter_id,
                added_by_user_id = EXCLUDED.added_by_user_id,
                added_at = NOW()""".command

    private val removeMessageFromChapterCommand: Command[Long *: Long *: EmptyTuple] =
      sql"DELETE FROM chapter_messages WHERE chapter_id = $int8 AND message_id = $int8".command

    private val upsertPreferenceQuery: Query[Long *: Long *: Boolean *: String *: EmptyTuple, ChapterPreference] =
      sql"""INSERT INTO chapter_user_preferences(chapter_id, user_id, is_important, mute_level, updated_at)
            VALUES ($int8, $int8, $bool, $varchar, NOW())
            ON CONFLICT (chapter_id, user_id)
            DO UPDATE SET is_important = EXCLUDED.is_important, mute_level = EXCLUDED.mute_level, updated_at = NOW()
            RETURNING chapter_id, is_important, mute_level::text, updated_at"""
        .query(int8 *: bool *: text *: instantCodec)
        .map { case (cid, imp, mute, ts) => ChapterPreference(cid, imp, mute, ts.toEpochMilli) }

    private val getPreferenceQuery: Query[Long *: Long *: EmptyTuple, ChapterPreference] =
      sql"SELECT chapter_id, is_important, mute_level::text, updated_at FROM chapter_user_preferences WHERE chapter_id = $int8 AND user_id = $int8"
        .query(int8 *: bool *: text *: instantCodec)
        .map { case (cid, imp, mute, ts) => ChapterPreference(cid, imp, mute, ts.toEpochMilli) }

    private val listPreferencesQuery: Query[Long *: Long *: Long *: Long *: EmptyTuple, ChapterPreference] =
      sql"""SELECT p.chapter_id, p.is_important, p.mute_level::text, p.updated_at
            FROM chapter_user_preferences p
            JOIN chapters c ON c.id = p.chapter_id
            LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = $int8
            WHERE p.user_id = $int8
              AND (
                c.owner_user_id = $int8
                OR c.visibility = 'public'
                OR c.visibility = 'authenticated'
                OR (c.visibility IN ('individuals', 'members') AND cm.user_id IS NOT NULL)
                OR (c.visibility = 'group' AND EXISTS (
                  SELECT 1 FROM chapter_group_access cga
                  JOIN group_members gm ON gm.group_id = cga.group_id
                  WHERE cga.chapter_id = c.id AND gm.user_id = $int8
                ))
              )
            ORDER BY p.updated_at DESC"""
        .query(int8 *: bool *: text *: instantCodec)
        .map { case (cid, imp, mute, ts) => ChapterPreference(cid, imp, mute, ts.toEpochMilli) }

    private val markReadCommand: Command[Long *: Long *: Long *: EmptyTuple] =
      sql"""INSERT INTO message_reads(message_id, chapter_id, user_id, read_at)
            VALUES ($int8, $int8, $int8, NOW())
            ON CONFLICT (message_id, user_id)
            DO UPDATE SET chapter_id = EXCLUDED.chapter_id, read_at = NOW()""".command

    private val markUnreadFromCommand: Command[Long *: Long *: Long *: EmptyTuple] =
      sql"""DELETE FROM message_reads mr
            USING chapter_messages cm
            WHERE mr.message_id = cm.message_id
              AND mr.user_id = $int8
              AND cm.chapter_id = $int8
              AND cm.message_id >= $int8""".command

    private val readMuteLevelQuery: Query[Long *: Long *: EmptyTuple, String] =
      sql"SELECT mute_level::text FROM chapter_user_preferences WHERE chapter_id = $int8 AND user_id = $int8".query(text)

    private val unreadCountQuery: Query[Long *: Long *: EmptyTuple, Long] =
      sql"""SELECT COUNT(*) AS unread_count
            FROM chapter_messages cm
            JOIN messages m ON m.id = cm.message_id
            LEFT JOIN message_reads mr ON mr.message_id = cm.message_id AND mr.user_id = $int8
            WHERE cm.chapter_id = $int8
              AND m.deleted = FALSE
              AND mr.message_id IS NULL""".query(int8)

    private val deleteChapterCommand: Command[Long] =
      sql"DELETE FROM chapters WHERE id = $int8".command

    private val checkMessageAuthorQuery: Query[Long, Long] =
      sql"SELECT author_user_id FROM messages WHERE id = $int8".query(int8)

    private val insertShareLinkCommand: Command[String *: Long *: Option[Long] *: Option[Long] *: Int *: EmptyTuple] =
      sql"""INSERT INTO share_links(token, owner_user_id, chapter_id, message_id, expires_at)
            VALUES ($varchar, $int8, ${int8.opt}, ${int8.opt}, NOW() + (${int4} * INTERVAL '1 day'))""".command

    private val resolveShareLinkQuery: Query[String, (Option[Long], Option[Long])] =
      sql"""SELECT chapter_id, message_id FROM share_links
            WHERE token = $varchar AND (expires_at IS NULL OR expires_at > NOW())"""
        .query(int8.opt *: int8.opt)

    private val listChapterMessagesQuery: Query[Long *: Int *: EmptyTuple, MessageData] =
      sql"""SELECT m.id, m.author_user_id, m.content, m.deleted, m.version, m.created_at, m.updated_at, m.client_edited_at
            FROM chapter_messages cm
            JOIN messages m ON m.id = cm.message_id
            WHERE cm.chapter_id = $int8
            ORDER BY cm.message_id DESC
            LIMIT $int4"""
        .query(int8 *: int8 *: text *: bool *: int4 *: instantCodec *: instantCodec *: optInstantCodec)
        .map { case (id, author, content, deleted, ver, created, updated, clientEdited) =>
          MessageData(id, author, content, deleted, ver, created, updated, clientEdited)
        }

    private val listChapterMessagesWithCursorQuery: Query[Long *: Long *: Int *: EmptyTuple, MessageData] =
      sql"""SELECT m.id, m.author_user_id, m.content, m.deleted, m.version, m.created_at, m.updated_at, m.client_edited_at
            FROM chapter_messages cm
            JOIN messages m ON m.id = cm.message_id
            WHERE cm.chapter_id = $int8 AND cm.message_id < $int8
            ORDER BY cm.message_id DESC
            LIMIT $int4"""
        .query(int8 *: int8 *: text *: bool *: int4 *: instantCodec *: instantCodec *: optInstantCodec)
        .map { case (id, author, content, deleted, ver, created, updated, clientEdited) =>
          MessageData(id, author, content, deleted, ver, created, updated, clientEdited)
        }

    private val checkReadAccessQuery: Query[Long *: Long *: Long *: EmptyTuple, (Long, String, Option[Long], Boolean)] =
      sql"""SELECT c.owner_user_id, c.visibility::text,
                cm.user_id AS member_user_id,
                EXISTS (
                  SELECT 1 FROM chapter_group_access cga
                  JOIN group_members gm ON gm.group_id = cga.group_id
                  WHERE cga.chapter_id = c.id AND gm.user_id = $int8
                ) AS in_group
             FROM chapters c
             LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = $int8
             WHERE c.id = $int8"""
        .query(int8 *: text *: int8.opt *: bool)

    private val checkOwnerQuery: Query[Long, Long] =
      sql"SELECT owner_user_id FROM chapters WHERE id = $int8".query(int8)

    private val checkOwnerOrEditorQuery: Query[Long *: Long *: EmptyTuple, (Long, Option[String])] =
      sql"""SELECT c.owner_user_id, cm.role::text
             FROM chapters c
             LEFT JOIN chapter_members cm ON cm.chapter_id = c.id AND cm.user_id = $int8
             WHERE c.id = $int8"""
        .query(int8 *: text.opt)

    private val checkMessageInChapterQuery: Query[Long *: Long *: EmptyTuple, Long] =
      sql"SELECT 1 FROM chapter_messages WHERE chapter_id = $int8 AND message_id = $int8".query(int4.imap(_.toLong)(_.toInt))

    // ---- Skunk auth helpers --------------------------------------------------

    private def requireReadAccessSkunk(session: Session[Task], userId: Long, chapterId: Long): Task[Unit] =
      session.option(checkReadAccessQuery)(userId *: userId *: chapterId *: EmptyTuple).flatMap {
        case None => ZIO.fail(new RuntimeException("Chapter not found"))
        case Some((ownerUserId, visibility, memberUserId, inGroup)) =>
          val isMember = memberUserId.contains(userId)
          val hasAccess =
            ownerUserId == userId ||
              visibility == "public" ||
              visibility == "authenticated" ||
              ((visibility == "individuals" || visibility == "members") && isMember) ||
              (visibility == "group" && inGroup)
          ZIO.fail(new RuntimeException("Not allowed: no read access to this chapter")).unless(hasAccess).unit
      }

    private def requireOwnerSkunk(session: Session[Task], userId: Long, chapterId: Long): Task[Unit] =
      session.option(checkOwnerQuery)(chapterId).flatMap {
        case None        => ZIO.fail(new RuntimeException("Chapter not found"))
        case Some(owner) => ZIO.fail(new RuntimeException("Not allowed: only the chapter owner can perform this action")).unless(owner == userId).unit
      }

    private def requireOwnerOrEditorSkunk(session: Session[Task], userId: Long, chapterId: Long): Task[Unit] =
      session.option(checkOwnerOrEditorQuery)(userId *: chapterId *: EmptyTuple).flatMap {
        case None => ZIO.fail(new RuntimeException("Chapter not found"))
        case Some((ownerUserId, memberRole)) =>
          val isOwner  = ownerUserId == userId
          val isEditor = memberRole.contains("editor")
          ZIO.fail(new RuntimeException("Not allowed: owner or editor role required")).unless(isOwner || isEditor).unit
      }

    private def requireMessageInChapterSkunk(session: Session[Task], chapterId: Long, messageId: Long): Task[Unit] =
      session.option(checkMessageInChapterQuery)(chapterId *: messageId *: EmptyTuple).flatMap {
        case None => ZIO.fail(new RuntimeException("Message does not belong to chapter"))
        case Some(_) => ZIO.unit
      }

    // ---- JDBC helpers (unchanged) --------------------------------------------

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

    // ---- Public API ----------------------------------------------------------

    override def createChapter(ownerUserId: Long, title: String, parentChapterId: Option[Long]): Task[Chapter] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.unique(createChapterQuery)((ownerUserId, title, parentChapterId)))
      } {
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

    override def listAccessibleChapters(userId: Long): Task[List[Chapter]] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.execute(listAccessibleChaptersQuery)(userId *: userId *: userId *: EmptyTuple))
      } {
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

    override def getChapterDetail(userId: Long, chapterId: Long): Task[ChapterDetail] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          for {
            _       <- requireReadAccessSkunk(session, userId, chapterId)
            chapter <- session.unique(getChapterRowQuery)(chapterId)
            members <- session.execute(getMembersQuery)(chapterId)
            msgIds  <- session.execute(getMessageIdsQuery)(chapterId)
          } yield ChapterDetail(chapter, members, msgIds)
        }
      } {
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
        parsedCursor      <- parseCursor(cursor)
        validatedPageSize <- validatePageSize(pageSize)
        page <- withSkunkOrJdbc {
          skunkPool.withSession { session =>
            for {
              _ <- requireReadAccessSkunk(session, userId, chapterId)
              fetchSize = validatedPageSize + 1
              rows <- parsedCursor match {
                case Some(value) =>
                  session.execute(listChapterMessagesWithCursorQuery)(chapterId *: value *: fetchSize *: EmptyTuple)
                case None =>
                  session.execute(listChapterMessagesQuery)(chapterId *: fetchSize *: EmptyTuple)
              }
            } yield paginateMessages(rows, validatedPageSize)
          }
        } {
          db.withConnection { connection =>
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
        }
      } yield page
    }

    override def updateVisibility(ownerUserId: Long, chapterId: Long, visibility: String): Task[Unit] = {
      val normalized = if visibility == "members" then "individuals" else visibility
      val allowed = Set("private", "individuals", "authenticated", "group", "public")
      if !allowed.contains(normalized) then
        ZIO.fail(new RuntimeException(s"Invalid visibility '$visibility': must be one of: ${allowed.mkString(", ")}"))
      else
        withSkunkOrJdbc {
          skunkPool.withSession { session =>
            requireOwnerSkunk(session, ownerUserId, chapterId) *>
              session.execute(updateVisibilityCommand)(normalized *: chapterId *: EmptyTuple).unit
          }
        } {
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
    }

    override def addMember(ownerUserId: Long, chapterId: Long, targetUserId: Long, role: String): Task[Unit] = {
      val allowed = Set("viewer", "editor")
      if !allowed.contains(role) then
        ZIO.fail(new RuntimeException(s"Invalid role '$role': must be viewer or editor"))
      else
        withSkunkOrJdbc {
          skunkPool.withSession { session =>
            requireOwnerSkunk(session, ownerUserId, chapterId) *>
              session.execute(addMemberCommand)(chapterId *: targetUserId *: role *: EmptyTuple).unit
          }
        } {
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
    }

    override def removeMember(ownerUserId: Long, chapterId: Long, targetUserId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireOwnerSkunk(session, ownerUserId, chapterId) *>
            session.execute(removeMemberCommand)(chapterId *: targetUserId *: EmptyTuple).unit
        }
      } {
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

    override def addMessageToChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireOwnerOrEditorSkunk(session, userId, chapterId) *>
            session.execute(addMessageToChapterCommand)(chapterId *: messageId *: userId *: EmptyTuple).unit
        }
      } {
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

    override def removeMessageFromChapter(userId: Long, chapterId: Long, messageId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireOwnerOrEditorSkunk(session, userId, chapterId) *>
            session.execute(removeMessageFromChapterCommand)(chapterId *: messageId *: EmptyTuple).unit
        }
      } {
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
        pref <- withSkunkOrJdbc {
          skunkPool.withSession { session =>
            requireReadAccessSkunk(session, userId, chapterId) *>
              session.unique(upsertPreferenceQuery)(chapterId *: userId *: isImportant *: normalizedMute *: EmptyTuple)
          }
        } {
          db.withConnection { connection =>
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
        }
      } yield pref
    }

    override def getPreference(userId: Long, chapterId: Long): Task[ChapterPreference] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireReadAccessSkunk(session, userId, chapterId) *>
            session.option(getPreferenceQuery)(chapterId *: userId *: EmptyTuple).map {
              case Some(p) => p
              case None    => ChapterPreference(chapterId = chapterId, isImportant = false, muteLevel = "none", updatedAtEpochMillis = 0L)
            }
        }
      } {
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

    override def listPreferences(userId: Long): Task[List[ChapterPreference]] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.execute(listPreferencesQuery)(userId *: userId *: userId *: userId *: EmptyTuple))
      } {
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

    override def markMessageRead(userId: Long, chapterId: Long, messageId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireReadAccessSkunk(session, userId, chapterId) *>
            requireMessageInChapterSkunk(session, chapterId, messageId) *>
            session.execute(markReadCommand)(messageId *: chapterId *: userId *: EmptyTuple).unit
        }
      } {
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

    override def markUnreadFrom(userId: Long, chapterId: Long, messageId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireReadAccessSkunk(session, userId, chapterId) *>
            requireMessageInChapterSkunk(session, chapterId, messageId) *>
            session.execute(markUnreadFromCommand)(userId *: chapterId *: messageId *: EmptyTuple).unit
        }
      } {
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

    override def unreadCount(userId: Long, chapterId: Long): Task[ChapterUnreadState] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireReadAccessSkunk(session, userId, chapterId) *>
            session.option(readMuteLevelQuery)(chapterId *: userId *: EmptyTuple).map(_.getOrElse("none")).flatMap { muteLevel =>
              if muteLevel == "hard" then ZIO.succeed(ChapterUnreadState(chapterId, 0, muteLevel))
              else session.unique(unreadCountQuery)(userId *: chapterId *: EmptyTuple).map(cnt => ChapterUnreadState(chapterId, cnt.toInt, muteLevel))
            }
        }
      } {
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

    override def deleteChapter(ownerUserId: Long, chapterId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireOwnerSkunk(session, ownerUserId, chapterId) *>
            session.execute(deleteChapterCommand)(chapterId).unit
        }
      } {
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
        withSkunkOrJdbc {
          skunkPool.withSession { session =>
            for {
              _ <- chapterId.fold(ZIO.unit)(cid => requireOwnerSkunk(session, ownerUserId, cid))
              _ <- messageId.fold(ZIO.unit) { mid =>
                session.option(checkMessageAuthorQuery)(mid).flatMap {
                  case None         => ZIO.fail(new RuntimeException("Message not found"))
                  case Some(author) => ZIO.fail(new RuntimeException("Not allowed: only the message author can create a share link")).unless(author == ownerUserId).unit
                }
              }
              token = generateToken()
              _ <- session.execute(insertShareLinkCommand)(token *: ownerUserId *: chapterId *: messageId *: shareLinkTtlDays *: EmptyTuple)
            } yield token
          }
        } {
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
    }

    override def resolveShareLink(token: String): Task[ShareLinkTarget] = {
      for {
        normalizedToken <- validateShareLinkToken(token)
        target <- withSkunkOrJdbc {
          skunkPool.withSession(_.option(resolveShareLinkQuery)(normalizedToken)).flatMap {
            case None => ZIO.fail(new RuntimeException("Share link not found or expired"))
            case Some((chapIdOpt, msgIdOpt)) =>
              val targetType = if chapIdOpt.isDefined then "chapter" else "message"
              ZIO.succeed(ShareLinkTarget(targetType, chapIdOpt, msgIdOpt))
          }
        } {
          db.withConnection { connection =>
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

    private def withSkunkOrJdbc[A](skunkEffect: Task[A])(jdbcEffect: => Task[A]): Task[A] =
      skunkEffect.catchSome {
        case ex: RuntimeException if ex.getMessage == "Skunk runtime is disabled" => jdbcEffect
      }
  }

  val layer: URLayer[Database & SkunkSessionPool, ChaptersService] = ZLayer {
    for {
      db        <- ZIO.service[Database]
      skunkPool <- ZIO.service[SkunkSessionPool]
    } yield new LiveChaptersService(db, skunkPool)
  }
}