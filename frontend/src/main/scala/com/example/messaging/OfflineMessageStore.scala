package com.example.messaging

import com.example.api.MessageResponse
import org.scalajs.dom
import zio.json.*

object OfflineMessageStore {

  final case class PendingOfflineMessage(
    chapterId: Long,
    tempId: Long,
    content: String,
    createdAtEpochMillis: Long,
    lastEditedAtEpochMillis: Long
  )
  object PendingOfflineMessage {
    given JsonEncoder[PendingOfflineMessage] = DeriveJsonEncoder.gen[PendingOfflineMessage]
    given JsonDecoder[PendingOfflineMessage] = DeriveJsonDecoder.gen[PendingOfflineMessage]
  }

  private def pendingKey(userId: Long): String = s"chat.offline.pending.$userId"
  private def cacheKey(userId: Long, chapterId: Long): String = s"chat.cache.messages.$userId.$chapterId"

  private def getRaw(key: String): Option[String] = Option(dom.window.localStorage.getItem(key)).filter(_.nonEmpty)
  private def setRaw(key: String, value: String): Unit = dom.window.localStorage.setItem(key, value)
  private def removeRaw(key: String): Unit = dom.window.localStorage.removeItem(key)

  def loadPending(userId: Long): Option[PendingOfflineMessage] =
    getRaw(pendingKey(userId)).flatMap(_.fromJson[PendingOfflineMessage].toOption)

  def savePending(userId: Long, pending: PendingOfflineMessage): Unit =
    setRaw(pendingKey(userId), pending.toJson)

  def clearPending(userId: Long): Unit =
    removeRaw(pendingKey(userId))

  def loadCachedMessages(userId: Long, chapterId: Long): List[MessageResponse] =
    getRaw(cacheKey(userId, chapterId))
      .flatMap(_.fromJson[List[MessageResponse]].toOption)
      .getOrElse(Nil)

  def saveCachedMessages(userId: Long, chapterId: Long, messages: List[MessageResponse], maxItems: Int = 100): Unit = {
    val bounded = messages.take(maxItems)
    setRaw(cacheKey(userId, chapterId), bounded.toJson)
  }

  def removeCachedMessages(userId: Long, chapterId: Long): Unit =
    removeRaw(cacheKey(userId, chapterId))

  def upsertSinglePending(
    existing: Option[PendingOfflineMessage],
    chapterId: Long,
    content: String,
    nowEpochMillis: Long
  ): Either[String, PendingOfflineMessage] = {
    val trimmed = content.trim
    if trimmed.isEmpty then Left("Message content cannot be empty")
    else
      existing match {
        case Some(_) =>
          Left("Only one offline pending message is supported")
        case None =>
          Right(
            PendingOfflineMessage(
              chapterId = chapterId,
              tempId = -nowEpochMillis,
              content = trimmed,
              createdAtEpochMillis = nowEpochMillis,
              lastEditedAtEpochMillis = nowEpochMillis
            )
          )
      }
  }

  def mergeCachedWithPending(
    cached: List[MessageResponse],
    pending: Option[PendingOfflineMessage],
    chapterId: Long,
    authorUserId: Long
  ): List[MessageResponse] = pending match {
    case Some(value) if value.chapterId == chapterId =>
      val offline = toOfflineMessage(value, authorUserId)
      if cached.exists(_.id == offline.id) then cached else offline :: cached
    case _ => cached
  }

  def toOfflineMessage(pending: PendingOfflineMessage, authorUserId: Long): MessageResponse =
    MessageResponse(
      id = pending.tempId,
      authorUserId = authorUserId,
      content = pending.content,
      deleted = false,
      version = 1,
      createdAtEpochMillis = pending.createdAtEpochMillis,
      updatedAtEpochMillis = pending.lastEditedAtEpochMillis,
      clientEditedAtEpochMillis = Some(pending.lastEditedAtEpochMillis),
      deepLink = s"/messages/offline?tempId=${pending.tempId}"
    )
}
