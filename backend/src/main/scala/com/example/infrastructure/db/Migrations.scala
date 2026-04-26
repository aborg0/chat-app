package com.example.infrastructure.db

import zio.*
import org.flywaydb.core.Flyway

object Migrations {
  def migrate(jdbcUrl: String, user: String, password: String): Task[Unit] = {
    ZIO.attemptBlocking {
      Flyway
        .configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
      ()
    }
  }
}