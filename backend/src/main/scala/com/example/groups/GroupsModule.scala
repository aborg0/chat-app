package com.example.groups

import zio.*
import com.example.infrastructure.db.{Database, SkunkSessionPool}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

object GroupsModule {

  final case class Group(
    id: Long,
    ownerUserId: Long,
    name: String,
    createdAtEpochMillis: Long
  )

  trait GroupsService {
    def createGroup(ownerUserId: Long, name: String): Task[Group]
    def listAccessibleGroups(userId: Long): Task[List[Group]]
    def deleteGroup(ownerUserId: Long, groupId: Long): Task[Unit]
    def addMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit]
    def removeMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit]
    def listMembers(userId: Long, groupId: Long): Task[List[Long]]
    def addChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit]
    def removeChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit]
    def listChapterGroupAccess(userId: Long, chapterId: Long): Task[List[Long]]
  }

  final class LiveGroupsService(db: Database, skunkPool: SkunkSessionPool) extends GroupsService {

    // ---------------------------------------------------------------------------
    // Skunk queries & commands
    // ---------------------------------------------------------------------------

    private val createGroupQuery: Query[Long *: String *: EmptyTuple, Group] =
      sql"INSERT INTO groups(owner_user_id, name) VALUES ($int8, $varchar) RETURNING id, owner_user_id, name::text, created_at"
        .query(int8 *: int8 *: text *: timestamptz)
        .map { case (id, owner, name, ts) => Group(id, owner, name, ts.toInstant.toEpochMilli) }

    private val listGroupsQuery: Query[Long *: Long *: EmptyTuple, Group] =
      sql"""SELECT g.id, g.owner_user_id, g.name::text, g.created_at
            FROM groups g
            WHERE g.owner_user_id = $int8
               OR EXISTS (SELECT 1 FROM group_members gm WHERE gm.group_id = g.id AND gm.user_id = $int8)
            ORDER BY g.created_at DESC"""
        .query(int8 *: int8 *: text *: timestamptz)
        .map { case (id, owner, name, ts) => Group(id, owner, name, ts.toInstant.toEpochMilli) }

    private val deleteGroupCommand: Command[Long] =
      sql"DELETE FROM groups WHERE id = $int8".command

    private val addMemberCommand: Command[Long *: Long *: EmptyTuple] =
      sql"INSERT INTO group_members(group_id, user_id) VALUES ($int8, $int8) ON CONFLICT DO NOTHING".command

    private val removeMemberCommand: Command[Long *: Long *: EmptyTuple] =
      sql"DELETE FROM group_members WHERE group_id = $int8 AND user_id = $int8".command

    private val listMembersQuery: Query[Long, Long] =
      sql"SELECT user_id FROM group_members WHERE group_id = $int8 ORDER BY joined_at"
        .query(int8)

    private val addChapterGroupAccessCommand: Command[Long *: Long *: EmptyTuple] =
      sql"INSERT INTO chapter_group_access(chapter_id, group_id) VALUES ($int8, $int8) ON CONFLICT DO NOTHING".command

    private val removeChapterGroupAccessCommand: Command[Long *: Long *: EmptyTuple] =
      sql"DELETE FROM chapter_group_access WHERE chapter_id = $int8 AND group_id = $int8".command

    private val listChapterGroupAccessQuery: Query[Long, Long] =
      sql"SELECT group_id FROM chapter_group_access WHERE chapter_id = $int8".query(int8)

    private val checkGroupOwnerQuery: Query[Long, Long] =
      sql"SELECT owner_user_id FROM groups WHERE id = $int8".query(int8)

    private val checkChapterOwnerQuery: Query[Long, Long] =
      sql"SELECT owner_user_id FROM chapters WHERE id = $int8".query(int8)

    private val checkChapterReadAccessQuery: Query[Long *: Long *: Long *: EmptyTuple, (Long, String, Option[Long], Boolean)] =
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

    // ---------------------------------------------------------------------------
    // Skunk helper checks
    // ---------------------------------------------------------------------------

    private def requireGroupOwnerSkunk(session: Session[Task], userId: Long, groupId: Long): Task[Unit] =
      session.option(checkGroupOwnerQuery)(groupId).flatMap {
        case None        => ZIO.fail(new RuntimeException("Group not found"))
        case Some(owner) => ZIO.fail(new RuntimeException("Not allowed: only the group owner can perform this action")).unless(owner == userId).unit
      }

    private def requireChapterOwnerSkunk(session: Session[Task], userId: Long, chapterId: Long): Task[Unit] =
      session.option(checkChapterOwnerQuery)(chapterId).flatMap {
        case None        => ZIO.fail(new RuntimeException("Chapter not found"))
        case Some(owner) => ZIO.fail(new RuntimeException("Not allowed: only the chapter owner can manage group access")).unless(owner == userId).unit
      }

    private def requireChapterReadAccessSkunk(session: Session[Task], userId: Long, chapterId: Long): Task[Unit] =
      session.option(checkChapterReadAccessQuery)(userId *: userId *: chapterId *: EmptyTuple).flatMap {
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

    // ---------------------------------------------------------------------------
    // JDBC helpers (unchanged)
    // ---------------------------------------------------------------------------

    private def requireGroupOwner(connection: java.sql.Connection, userId: Long, groupId: Long): Unit = {
      val stmt = connection.prepareStatement("SELECT owner_user_id FROM groups WHERE id = ?")
      try {
        stmt.setLong(1, groupId)
        val rs = stmt.executeQuery()
        if !rs.next() then throw new RuntimeException("Group not found")
        val ownerUserId = rs.getLong("owner_user_id")
        rs.close()
        if ownerUserId != userId then throw new RuntimeException("Not allowed: only the group owner can perform this action")
      } finally {
        stmt.close()
      }
    }

    private def requireChapterOwner(connection: java.sql.Connection, userId: Long, chapterId: Long): Unit = {
      val stmt = connection.prepareStatement("SELECT owner_user_id FROM chapters WHERE id = ?")
      try {
        stmt.setLong(1, chapterId)
        val rs = stmt.executeQuery()
        if !rs.next() then throw new RuntimeException("Chapter not found")
        val ownerUserId = rs.getLong("owner_user_id")
        rs.close()
        if ownerUserId != userId then throw new RuntimeException("Not allowed: only the chapter owner can manage group access")
      } finally {
        stmt.close()
      }
    }

    private def requireChapterReadAccess(connection: java.sql.Connection, userId: Long, chapterId: Long): Unit = {
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

    // ---------------------------------------------------------------------------
    // Public API — Skunk-first with JDBC fallback
    // ---------------------------------------------------------------------------

    override def createGroup(ownerUserId: Long, name: String): Task[Group] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.unique(createGroupQuery)(ownerUserId *: name *: EmptyTuple))
      } {
        db.withConnection { connection =>
          val stmt = connection.prepareStatement(
            "INSERT INTO groups(owner_user_id, name) VALUES (?, ?) RETURNING id, created_at"
          )
          try {
            stmt.setLong(1, ownerUserId)
            stmt.setString(2, name)
            val rs = stmt.executeQuery()
            rs.next()
            val id = rs.getLong("id")
            val createdAt = rs.getTimestamp("created_at").toInstant.toEpochMilli
            rs.close()
            Group(id, ownerUserId, name, createdAt)
          } finally {
            stmt.close()
          }
        }
      }

    override def listAccessibleGroups(userId: Long): Task[List[Group]] =
      withSkunkOrJdbc {
        skunkPool.withSession(_.execute(listGroupsQuery)(userId *: userId *: EmptyTuple))
      } {
        db.withConnection { connection =>
          val stmt = connection.prepareStatement(
            """SELECT g.id, g.owner_user_id, g.name, g.created_at
               FROM groups g
               WHERE g.owner_user_id = ?
                  OR EXISTS (SELECT 1 FROM group_members gm WHERE gm.group_id = g.id AND gm.user_id = ?)
               ORDER BY g.created_at DESC"""
          )
          try {
            stmt.setLong(1, userId)
            stmt.setLong(2, userId)
            val rs = stmt.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[Group]
            while rs.next() do {
              buf += Group(
                id = rs.getLong("id"),
                ownerUserId = rs.getLong("owner_user_id"),
                name = rs.getString("name"),
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

    override def deleteGroup(ownerUserId: Long, groupId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireGroupOwnerSkunk(session, ownerUserId, groupId) *>
            session.execute(deleteGroupCommand)(groupId).unit
        }
      } {
        db.withConnection { connection =>
          requireGroupOwner(connection, ownerUserId, groupId)
          val stmt = connection.prepareStatement("DELETE FROM groups WHERE id = ?")
          try {
            stmt.setLong(1, groupId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
      }

    override def addMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireGroupOwnerSkunk(session, ownerUserId, groupId) *>
            session.execute(addMemberCommand)(groupId *: targetUserId *: EmptyTuple).unit
        }
      } {
        db.withConnection { connection =>
          requireGroupOwner(connection, ownerUserId, groupId)
          val stmt = connection.prepareStatement(
            "INSERT INTO group_members(group_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
          )
          try {
            stmt.setLong(1, groupId)
            stmt.setLong(2, targetUserId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
      }

    override def removeMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireGroupOwnerSkunk(session, ownerUserId, groupId) *>
            session.execute(removeMemberCommand)(groupId *: targetUserId *: EmptyTuple).unit
        }
      } {
        db.withConnection { connection =>
          requireGroupOwner(connection, ownerUserId, groupId)
          val stmt = connection.prepareStatement(
            "DELETE FROM group_members WHERE group_id = ? AND user_id = ?"
          )
          try {
            stmt.setLong(1, groupId)
            stmt.setLong(2, targetUserId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
      }

    override def listMembers(userId: Long, groupId: Long): Task[List[Long]] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireGroupOwnerSkunk(session, userId, groupId) *>
            session.execute(listMembersQuery)(groupId)
        }
      } {
        db.withConnection { connection =>
          requireGroupOwner(connection, userId, groupId)
          val stmt = connection.prepareStatement(
            "SELECT user_id FROM group_members WHERE group_id = ? ORDER BY joined_at"
          )
          try {
            stmt.setLong(1, groupId)
            val rs = stmt.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[Long]
            while rs.next() do buf += rs.getLong("user_id")
            rs.close()
            buf.toList
          } finally {
            stmt.close()
          }
        }
      }

    override def addChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireChapterOwnerSkunk(session, ownerUserId, chapterId) *>
            session.execute(addChapterGroupAccessCommand)(chapterId *: groupId *: EmptyTuple).unit
        }
      } {
        db.withConnection { connection =>
          requireChapterOwner(connection, ownerUserId, chapterId)
          val stmt = connection.prepareStatement(
            "INSERT INTO chapter_group_access(chapter_id, group_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
          )
          try {
            stmt.setLong(1, chapterId)
            stmt.setLong(2, groupId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
      }

    override def removeChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireChapterOwnerSkunk(session, ownerUserId, chapterId) *>
            session.execute(removeChapterGroupAccessCommand)(chapterId *: groupId *: EmptyTuple).unit
        }
      } {
        db.withConnection { connection =>
          requireChapterOwner(connection, ownerUserId, chapterId)
          val stmt = connection.prepareStatement(
            "DELETE FROM chapter_group_access WHERE chapter_id = ? AND group_id = ?"
          )
          try {
            stmt.setLong(1, chapterId)
            stmt.setLong(2, groupId)
            stmt.executeUpdate()
            ()
          } finally {
            stmt.close()
          }
        }
      }

    override def listChapterGroupAccess(userId: Long, chapterId: Long): Task[List[Long]] =
      withSkunkOrJdbc {
        skunkPool.withSession { session =>
          requireChapterReadAccessSkunk(session, userId, chapterId) *>
            session.execute(listChapterGroupAccessQuery)(chapterId)
        }
      } {
        db.withConnection { connection =>
          requireChapterReadAccess(connection, userId, chapterId)
          val stmt = connection.prepareStatement(
            "SELECT group_id FROM chapter_group_access WHERE chapter_id = ?"
          )
          try {
            stmt.setLong(1, chapterId)
            val rs = stmt.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[Long]
            while rs.next() do buf += rs.getLong("group_id")
            rs.close()
            buf.toList
          } finally {
            stmt.close()
          }
        }
      }

    private def withSkunkOrJdbc[A](skunkEffect: Task[A])(jdbcEffect: => Task[A]): Task[A] =
      skunkEffect.catchSome {
        case ex: RuntimeException if ex.getMessage == "Skunk runtime is disabled" => jdbcEffect
      }
  }

  val layer: URLayer[Database & SkunkSessionPool, GroupsService] = ZLayer {
    for {
      db        <- ZIO.service[Database]
      skunkPool <- ZIO.service[SkunkSessionPool]
    } yield new LiveGroupsService(db, skunkPool)
  }
}