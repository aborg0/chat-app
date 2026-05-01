package com.example.messaging

import com.example.api.TypingIndicatorResponse
import zio.*
import zio.stream.*

import java.util.concurrent.TimeUnit

object TypingModule {

  final case class TypingState(
    data: Map[Long, Map[Long, (String, Long)]],
    typingTimeoutMillis: Long = 5000L,
    cleanupGraceMillis: Long = 1000L
  )

  trait TypingService {
    def startTyping(userId: Long, username: String, chapterId: Long): Task[Unit]
    def stopTyping(userId: Long, chapterId: Long): Task[Unit]
    def getTypingUsers(chapterId: Long): Task[List[TypingIndicatorResponse]]
    def subscribeToTypingEvents(chapterId: Long): ZStream[Any, Nothing, TypingIndicatorResponse]
  }

  final class LiveTypingService(
    stateRef: Ref[TypingState],
    subscriptions: Ref[Map[Long, Chunk[Hub[TypingIndicatorResponse]]]]
  ) extends TypingService {

    def startTyping(userId: Long, username: String, chapterId: Long): Task[Unit] =
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_.toLong)
        _ <- stateRef.update { state =>
          val chapterState = state.data.getOrElse(chapterId, Map.empty)
          state.copy(data = state.data.updated(chapterId, chapterState.updated(userId, (username, now))))
        }
        _ <- broadcast(chapterId, TypingIndicatorResponse(userId, username, chapterId, None))
      } yield ()

    def stopTyping(userId: Long, chapterId: Long): Task[Unit] =
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_.toLong)
        username <- stateRef.get.map(_.data.get(chapterId).flatMap(_.get(userId)).map(_._1).getOrElse("unknown"))
        _ <- stateRef.update { state =>
          val nextChapterState = state.data.getOrElse(chapterId, Map.empty) - userId
          if nextChapterState.isEmpty then state.copy(data = state.data - chapterId)
          else state.copy(data = state.data.updated(chapterId, nextChapterState))
        }
        _ <- broadcast(chapterId, TypingIndicatorResponse(userId, username, chapterId, Some(now)))
      } yield ()

    def getTypingUsers(chapterId: Long): Task[List[TypingIndicatorResponse]] =
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_.toLong)
        state <- stateRef.get
      } yield state.data
        .getOrElse(chapterId, Map.empty)
        .collect {
          case (userId, (username, lastActivity)) if now - lastActivity < state.typingTimeoutMillis =>
            TypingIndicatorResponse(userId, username, chapterId, None)
        }
        .toList

    def subscribeToTypingEvents(chapterId: Long): ZStream[Any, Nothing, TypingIndicatorResponse] =
      ZStream.unwrap {
        Hub.unbounded[TypingIndicatorResponse].map { hub =>
          val register = subscriptions.update { current =>
            current.updated(chapterId, current.getOrElse(chapterId, Chunk.empty) :+ hub)
          }
          ZStream.fromZIO(register) *> ZStream.fromHub(hub)
        }
      }

    private def broadcast(chapterId: Long, event: TypingIndicatorResponse): Task[Unit] =
      subscriptions.get.flatMap { current =>
        ZIO.foreachDiscard(current.getOrElse(chapterId, Chunk.empty))(hub => hub.publish(event).unit)
      }
  }

  val live: ULayer[TypingService] =
    ZLayer {
      for {
        stateRef <- Ref.make(TypingState(Map.empty))
        subscriptions <- Ref.make(Map.empty[Long, Chunk[Hub[TypingIndicatorResponse]]])
        service = new LiveTypingService(stateRef, subscriptions)
        _ <- cleanupLoop(stateRef).forkDaemon
      } yield service
    }

  private def cleanupLoop(stateRef: Ref[TypingState]): UIO[Unit] =
    (for {
      _ <- ZIO.sleep(1.second)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_.toLong)
      _ <- stateRef.update { state =>
        val timeout = state.typingTimeoutMillis + state.cleanupGraceMillis
        val cleaned = state.data.view
          .mapValues(_.filter { case (_, (_, lastActivity)) => now - lastActivity < timeout })
          .filter(_._2.nonEmpty)
          .toMap
        state.copy(data = cleaned)
      }
    } yield ()).forever
}
