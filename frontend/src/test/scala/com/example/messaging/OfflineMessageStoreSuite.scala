package com.example.messaging

import com.example.api.MessageResponse
import munit.FunSuite

class OfflineMessageStoreSuite extends FunSuite {

  test("upsertSinglePending creates new pending message with negative temp id") {
    val now = 1710000000000L
    val result = OfflineMessageStore.upsertSinglePending(None, chapterId = 42L, content = "hello", nowEpochMillis = now)

    assert(result.isRight)
    val pending = result.toOption.get
    assertEquals(pending.chapterId, 42L)
    assertEquals(pending.content, "hello")
    assert(pending.tempId < 0)
    assertEquals(pending.createdAtEpochMillis, now)
    assertEquals(pending.lastEditedAtEpochMillis, now)
  }

  test("upsertSinglePending rejects second pending message even in same chapter") {
    val existing = OfflineMessageStore.PendingOfflineMessage(
      chapterId = 7L,
      tempId = -100L,
      content = "old",
      createdAtEpochMillis = 100L,
      lastEditedAtEpochMillis = 100L
    )

    val result = OfflineMessageStore.upsertSinglePending(Some(existing), chapterId = 7L, content = "new text", nowEpochMillis = 200L)
    assert(result.isLeft)
  }

  test("upsertSinglePending rejects second chapter pending message") {
    val existing = OfflineMessageStore.PendingOfflineMessage(
      chapterId = 1L,
      tempId = -1L,
      content = "pending",
      createdAtEpochMillis = 1L,
      lastEditedAtEpochMillis = 1L
    )

    val result = OfflineMessageStore.upsertSinglePending(Some(existing), chapterId = 2L, content = "other", nowEpochMillis = 2L)
    assert(result.isLeft)
  }

  test("mergeCachedWithPending prepends offline message for matching chapter") {
    val cached = List(
      MessageResponse(10L, 1L, "cached", deleted = false, version = 2, 1000L, 1000L, None, "/messages/by-id?messageId=10")
    )
    val pending = Some(
      OfflineMessageStore.PendingOfflineMessage(
        chapterId = 5L,
        tempId = -500L,
        content = "offline",
        createdAtEpochMillis = 500L,
        lastEditedAtEpochMillis = 700L
      )
    )

    val merged = OfflineMessageStore.mergeCachedWithPending(cached, pending, chapterId = 5L, authorUserId = 99L)
    assertEquals(merged.head.id, -500L)
    assertEquals(merged.head.content, "offline")
    assertEquals(merged(1).id, 10L)
  }
}
