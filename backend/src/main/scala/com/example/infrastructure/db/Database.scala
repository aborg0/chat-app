package com.example.infrastructure.db

import zio.*
import java.sql.Connection
import java.sql.DriverManager

trait Database {
  def withConnection[A](f: Connection => A): Task[A]
}

object Database {
  def layer(url: String, user: String, password: String): ULayer[Database] =
    ZLayer.succeed(new JdbcDatabase(url, user, password))
}

final class JdbcDatabase(jdbcUrl: String, user: String, password: String) extends Database {
  override def withConnection[A](f: Connection => A): Task[A] = {
    ZIO.attemptBlocking {
      val connection = DriverManager.getConnection(jdbcUrl, user, password)
      try {
        f(connection)
      } finally {
        connection.close()
      }
    }
  }
}
