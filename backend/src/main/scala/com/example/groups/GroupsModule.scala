package com.example.groups

import zio.*
import com.example.infrastructure.db.Database

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

  final class LiveGroupsService(db: Database) extends GroupsService {

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

    override def createGroup(ownerUserId: Long, name: String): Task[Group] = {
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

    override def listAccessibleGroups(userId: Long): Task[List[Group]] = {
      db.withConnection { connection =>
        val stmt = connection.prepareStatement(
          """SELECT g.id, g.owner_user_id, g.name, g.created_at
             FROM groups g
             WHERE g.owner_user_id = ?
                OR EXISTS (
                  SELECT 1 FROM group_members gm
                  WHERE gm.group_id = g.id AND gm.user_id = ?
                )
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

    override def deleteGroup(ownerUserId: Long, groupId: Long): Task[Unit] = {
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

    override def addMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit] = {
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

    override def removeMember(ownerUserId: Long, groupId: Long, targetUserId: Long): Task[Unit] = {
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

    override def listMembers(userId: Long, groupId: Long): Task[List[Long]] = {
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

    override def addChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit] = {
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

    override def removeChapterGroupAccess(ownerUserId: Long, chapterId: Long, groupId: Long): Task[Unit] = {
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

    override def listChapterGroupAccess(userId: Long, chapterId: Long): Task[List[Long]] = {
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
  }

  val layer: URLayer[Database, GroupsService] = ZLayer {
    ZIO.serviceWith[Database](new LiveGroupsService(_))
  }
}