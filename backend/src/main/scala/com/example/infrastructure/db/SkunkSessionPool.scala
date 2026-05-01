package com.example.infrastructure.db

import cats.effect.Resource
import cats.effect.std.Console
import fs2.io.net.Network
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session
import zio.*
import zio.interop.catz.*

import java.net.URI

trait SkunkSessionPool {
  def withSession[A](f: Session[Task] => Task[A]): Task[A]
}

object SkunkSessionPool {
  def layer(jdbcUrl: String, user: String, password: String, maxSessions: Int): ZLayer[Any, Throwable, SkunkSessionPool] =
    ZLayer.scoped {
      for {
        address <- JdbcUrlAddress.fromJdbcUrl(jdbcUrl)
        allocated <- ZIO.acquireRelease(poolResource(address, user, password, maxSessions).allocated) {
          case (_, release) => release.orDie
        }
      } yield {
        new LiveSkunkSessionPool(allocated._1)
      }
    }

  val disabled: ULayer[SkunkSessionPool] = ZLayer.succeed {
    new SkunkSessionPool {
      override def withSession[A](f: Session[Task] => Task[A]): Task[A] =
        ZIO.fail(new RuntimeException("Skunk runtime is disabled"))
    }
  }

  private def poolResource(
    address: JdbcUrlAddress,
    user: String,
    password: String,
    maxSessions: Int
  ): Resource[Task, Resource[Task, Session[Task]]] = {
    given Tracer[Task] = Tracer.noop[Task]
    given Meter[Task] = Meter.noop[Task]
    given Console[Task] = Console.make[Task]
    given Network[Task] = Network.forAsync[Task]

    Session.Builder[Task]
      .withHost(address.host)
      .withPort(address.port)
      .withUserAndPassword(user, password)
      .withDatabase(address.database)
      .pooled(maxSessions)
  }

  private final class LiveSkunkSessionPool(pool: Resource[Task, Session[Task]]) extends SkunkSessionPool {
    override def withSession[A](f: Session[Task] => Task[A]): Task[A] = pool.use(f)
  }

  private final case class JdbcUrlAddress(host: String, port: Int, database: String)

  private object JdbcUrlAddress {
    def fromJdbcUrl(jdbcUrl: String): Task[JdbcUrlAddress] = ZIO.attempt {
      val prefix = "jdbc:postgresql://"
      if !jdbcUrl.startsWith(prefix) then {
        throw new RuntimeException("Unsupported JDBC URL format for Skunk")
      }

      val uri = URI.create(jdbcUrl.stripPrefix("jdbc:"))
      val host = Option(uri.getHost).getOrElse {
        throw new RuntimeException("JDBC URL is missing host")
      }
      val database = Option(uri.getPath)
        .map(_.stripPrefix("/"))
        .filter(_.nonEmpty)
        .getOrElse {
          throw new RuntimeException("JDBC URL is missing database name")
        }
      val port = if uri.getPort == -1 then 5432 else uri.getPort

      JdbcUrlAddress(host, port, database)
    }
  }
}
