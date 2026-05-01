package com.example.messaging

import com.example.api.MessageResponse
import munit.FunSuite

class DraftStoreSuite extends FunSuite {

  test("upsertPendingOfflineDraft creates a pending offline draft with negative temp id") {
    val now = 1710000000000L

    val result = DraftStore.upsertPendingOfflineDraft(None, chapterId = 42L, content = "hello", nowEpochMillis = now)

    assert(result.isRight)
    val draft = result.toOption.get
    assertEquals(draft.chapterId, 42L)
    assertEquals(draft.content, "hello")
    assert(draft.isPendingOffline)
    assert(draft.pendingOffline.exists(_.tempId < 0))
  }

  test("upsertPendingOfflineDraft rejects a second pending offline draft in the same chapter") {
    val existing = DraftStore.ChapterDraft.pendingOffline(chapterId = 7L, content = "old", nowEpochMillis = 100L)

    val result = DraftStore.upsertPendingOfflineDraft(Some(existing), chapterId = 7L, content = "new text", nowEpochMillis = 200L)

    assert(result.isLeft)
  }

  test("upsertPendingOfflineDraft allows a pending draft in a different chapter") {
    val existing = DraftStore.ChapterDraft.pendingOffline(chapterId = 1L, content = "pending", nowEpochMillis = 1L)

    val result = DraftStore.upsertPendingOfflineDraft(Some(DraftStore.ChapterDraft.typing(1L, existing.content)), chapterId = 2L, content = "other", nowEpochMillis = 2L)

    assert(result.isRight)
    assertEquals(result.toOption.get.chapterId, 2L)
  }

  test("upsertTypingDraft preserves an existing pending offline draft") {
    val pending = DraftStore.ChapterDraft.pendingOffline(chapterId = 5L, content = "offline", nowEpochMillis = 500L)

    val result = DraftStore.upsertTypingDraft(Some(pending), chapterId = 5L, content = "typed")

    assertEquals(result, Some(pending))
  }

  test("mergeCachedWithDraft prepends offline message for matching chapter") {
    val cached = List(
      MessageResponse(10L, 1L, "cached", deleted = false, version = 2, 1000L, 1000L, None, "/messages/by-id?messageId=10")
    )
    val draft = Some(DraftStore.ChapterDraft.pendingOffline(chapterId = 5L, content = "offline", nowEpochMillis = 700L))

    val merged = DraftStore.mergeCachedWithDraft(cached, draft, chapterId = 5L, authorUserId = 99L)

    assertEquals(merged.head.content, "offline")
    assertEquals(merged(1).id, 10L)
  }
}