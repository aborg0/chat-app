package com.example.messaging

import com.example.api.DraftResponse
import zio.*

import java.util.concurrent.TimeUnit

object DraftsModule {

  trait DraftsService {
    def getDraft(userId: Long, chapterId: Long): Task[Option[DraftResponse]]
    def getAllDrafts(userId: Long): Task[List[DraftResponse]]
    def saveDraft(userId: Long, chapterId: Long, content: String): Task[DraftResponse]
    def deleteDraft(userId: Long, chapterId: Long): Task[Unit]
  }

  private type DraftKey = (Long, Long)

  final class LiveDraftsService(stateRef: Ref[Map[DraftKey, DraftResponse]]) extends DraftsService {

    def getDraft(userId: Long, chapterId: Long): Task[Option[DraftResponse]] =
      stateRef.get.map(_.get((userId, chapterId)))

    def getAllDrafts(userId: Long): Task[List[DraftResponse]] =
      stateRef.get.map(
        _.collect { case ((draftUserId, _), draft) if draftUserId == userId => draft }
          .toList
          .sortBy(_.lastModifiedAtEpochMillis)(using Ordering.Long.reverse)
      )

    def saveDraft(userId: Long, chapterId: Long, content: String): Task[DraftResponse] =
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_.toLong)
        draft = DraftResponse(chapterId, userId, content, now)
        _ <- stateRef.update(_.updated((userId, chapterId), draft))
      } yield draft

    def deleteDraft(userId: Long, chapterId: Long): Task[Unit] =
      stateRef.update(_ - ((userId, chapterId))).unit
  }

  val live: ULayer[DraftsService] =
    ZLayer {
      Ref.make(Map.empty[DraftKey, DraftResponse]).map(new LiveDraftsService(_))
    }
}
