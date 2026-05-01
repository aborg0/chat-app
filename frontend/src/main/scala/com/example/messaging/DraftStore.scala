package com.example.messaging

import com.example.api.MessageResponse
import org.scalajs.dom
import zio.json.*

object DraftStore {

  final case class PendingOfflineState(
    tempId: Long,
    createdAtEpochMillis: Long,
    lastEditedAtEpochMillis: Long
  )
  object PendingOfflineState {
    given JsonEncoder[PendingOfflineState] = DeriveJsonEncoder.gen[PendingOfflineState]
    given JsonDecoder[PendingOfflineState] = DeriveJsonDecoder.gen[PendingOfflineState]
  }

  final case class ChapterDraft(
    chapterId: Long,
    content: String,
    draftType: String,
    pendingOffline: Option[PendingOfflineState]
  ) {
    def isTyping: Boolean = draftType == "typing"
    def isPendingOffline: Boolean = draftType == "pending-offline"
  }
  object ChapterDraft {
    given JsonEncoder[ChapterDraft] = DeriveJsonEncoder.gen[ChapterDraft]
    given JsonDecoder[ChapterDraft] = DeriveJsonDecoder.gen[ChapterDraft]

    def typing(chapterId: Long, content: String): ChapterDraft =
      ChapterDraft(chapterId, content.trim, "typing", None)

    def pendingOffline(chapterId: Long, content: String, nowEpochMillis: Long): ChapterDraft =
      ChapterDraft(
        chapterId = chapterId,
        content = content.trim,
        draftType = "pending-offline",
        pendingOffline = Some(PendingOfflineState(-nowEpochMillis, nowEpochMillis, nowEpochMillis))
      )
  }

  private def draftKey(userId: Long, chapterId: Long): String = s"chat.draft.$userId.$chapterId"
  private def oldPendingKey(userId: Long): String = s"chat.offline.pending.$userId"
  private def cacheKey(userId: Long, chapterId: Long): String = s"chat.cache.messages.$userId.$chapterId"

  private def getRaw(key: String): Option[String] = Option(dom.window.localStorage.getItem(key)).filter(_.nonEmpty)
  private def setRaw(key: String, value: String): Unit = dom.window.localStorage.setItem(key, value)
  private def removeRaw(key: String): Unit = dom.window.localStorage.removeItem(key)

  def loadDraft(userId: Long, chapterId: Long): Option[ChapterDraft] =
    getRaw(draftKey(userId, chapterId))
      .flatMap(_.fromJson[ChapterDraft].toOption)
      .orElse(migrateLegacyPendingDraft(userId).filter(_.chapterId == chapterId))

  def loadAllPendingOfflineDrafts(userId: Long): List[ChapterDraft] =
    localStorageKeys()
      .filter(_.startsWith(s"chat.draft.$userId."))
      .flatMap(key => getRaw(key).flatMap(_.fromJson[ChapterDraft].toOption))
      .filter(_.isPendingOffline)

  def saveDraft(userId: Long, draft: ChapterDraft): Unit =
    if draft.content.trim.isEmpty then clearDraft(userId, draft.chapterId)
    else setRaw(draftKey(userId, draft.chapterId), draft.copy(content = draft.content.trim).toJson)

  def clearDraft(userId: Long, chapterId: Long): Unit =
    removeRaw(draftKey(userId, chapterId))

  def saveCachedMessages(userId: Long, chapterId: Long, messages: List[MessageResponse], maxItems: Int = 100): Unit = {
    val bounded = messages.take(maxItems)
    setRaw(cacheKey(userId, chapterId), bounded.toJson)
  }

  def loadCachedMessages(userId: Long, chapterId: Long): List[MessageResponse] =
    getRaw(cacheKey(userId, chapterId))
      .flatMap(_.fromJson[List[MessageResponse]].toOption)
      .getOrElse(Nil)

  def removeCachedMessages(userId: Long, chapterId: Long): Unit =
    removeRaw(cacheKey(userId, chapterId))

  def upsertTypingDraft(existing: Option[ChapterDraft], chapterId: Long, content: String): Option[ChapterDraft] = {
    val trimmed = content.trim
    if trimmed.isEmpty then None
    else if existing.exists(_.isPendingOffline) then existing
    else Some(ChapterDraft.typing(chapterId, trimmed))
  }

  def upsertPendingOfflineDraft(existing: Option[ChapterDraft], chapterId: Long, content: String, nowEpochMillis: Long): Either[String, ChapterDraft] = {
    val trimmed = content.trim
    if trimmed.isEmpty then Left("Message content cannot be empty")
    else if existing.exists(_.isPendingOffline) then Left("A message is already pending offline for this chapter")
    else Right(ChapterDraft.pendingOffline(chapterId, trimmed, nowEpochMillis))
  }

  def mergeCachedWithDraft(
    cached: List[MessageResponse],
    draft: Option[ChapterDraft],
    chapterId: Long,
    authorUserId: Long
  ): List[MessageResponse] =
    draft match {
      case Some(value) if value.chapterId == chapterId && value.isPendingOffline =>
        val offline = toOfflineMessage(value, authorUserId)
        if cached.exists(_.id == offline.id) then cached else offline :: cached
      case _ =>
        cached
    }

  def toOfflineMessage(draft: ChapterDraft, authorUserId: Long): MessageResponse = {
    val pending = draft.pendingOffline.getOrElse(
      throw new IllegalArgumentException("Pending offline metadata is required for offline messages")
    )
    MessageResponse(
      id = pending.tempId,
      authorUserId = authorUserId,
      content = draft.content,
      deleted = false,
      version = 1,
      createdAtEpochMillis = pending.createdAtEpochMillis,
      updatedAtEpochMillis = pending.lastEditedAtEpochMillis,
      clientEditedAtEpochMillis = Some(pending.lastEditedAtEpochMillis),
      deepLink = s"/messages/offline?tempId=${pending.tempId}"
    )
  }

  private def migrateLegacyPendingDraft(userId: Long): Option[ChapterDraft] =
    getRaw(oldPendingKey(userId)).flatMap { raw =>
      raw.fromJson[OfflineMessageStore.PendingOfflineMessage].toOption.map { legacy =>
        val migrated = ChapterDraft(
          chapterId = legacy.chapterId,
          content = legacy.content,
          draftType = "pending-offline",
          pendingOffline = Some(
            PendingOfflineState(
              tempId = legacy.tempId,
              createdAtEpochMillis = legacy.createdAtEpochMillis,
              lastEditedAtEpochMillis = legacy.lastEditedAtEpochMillis
            )
          )
        )
        saveDraft(userId, migrated)
        removeRaw(oldPendingKey(userId))
        migrated
      }
    }

  private def localStorageKeys(): List[String] =
    (0 until dom.window.localStorage.length)
      .toList
      .flatMap(index => Option(dom.window.localStorage.key(index)))
}