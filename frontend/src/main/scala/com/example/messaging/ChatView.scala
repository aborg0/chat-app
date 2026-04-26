package com.example.messaging

import com.example.api.*
import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.timers

object ChatView {
  def view(auth: AuthResponse): HtmlElement = {
    val statusVar = Var("Syncing...")

    val chaptersVar = Var(List.empty[ChapterResponse])
    val preferencesVar = Var(Map.empty[Long, ChapterPreferenceResponse])
    val unreadByChapterVar = Var(Map.empty[Long, ChapterUnreadCountResponse])
    val selectedChapterIdVar = Var(Option.empty[Long])

    val newMessageVar = Var("")
    val searchVar = Var("")
    val searchTargetUserIdVar = Var("")
    val selectedMessageIdVar = Var("")
    val editContentVar = Var("")
    val messagesVar = Var(List.empty[MessageResponse])
    val selectedMessageVar = Var(Option.empty[MessageResponse])
    val historyVar = Var(List.empty[MessageHistoryEntry])

    val auditTargetUserVar = Var("")
    val auditEntriesVar = Var(List.empty[AuditEntryResponse])
    val searchCursorVar = Var(Option.empty[String])
    val searchNextCursorVar = Var(Option.empty[String])
    val chapterCursorVar = Var(Option.empty[String])
    val chapterNextCursorVar = Var(Option.empty[String])
    val auditCursorVar = Var(Option.empty[String])
    val auditNextCursorVar = Var(Option.empty[String])
    val isLoadingMoreVar = Var(false)
    val isChapterViewVar = Var(false)
    val firstUnreadIdVar = Var(Option.empty[Long])
    val messageListRef = Var(Option.empty[dom.Element])
    val visibleFullySeenIdsVar = Var(Set.empty[Long])
    val pendingAutoReadIdsVar = Var(Set.empty[Long])
    val autoReadMarkedIdsVar = Var(Set.empty[Long])
    val autoReadBlockedFromMessageIdVar = Var(Option.empty[Long])
    val autoReadInFlightVar = Var(false)
    val isOnlineVar = Var(dom.window.navigator.onLine)
    val pendingOfflineMessageVar = Var(OfflineMessageStore.loadPending(auth.userId))
    val cachedMessagesVar = Var(Map.empty[Long, List[MessageResponse]])
    val lastSelectedChapterIdVar = Var(Option.empty[Long])

    val defaultPageSize = 12

    def parseOptionalLong(input: String): Option[Long] = {
      val text = input.trim
      if text.isEmpty then None else scala.util.Try(text.toLong).toOption
    }

    def nowEpochMillis(): Long = js.Date.now().toLong

    def onlineNow(): Boolean = isOnlineVar.now() && dom.window.navigator.onLine

    def resolveCurrentChapterIdForOfflineQueue(): Option[Long] = {
      selectedChapterIdVar.now()
        .orElse(lastSelectedChapterIdVar.now())
        .orElse {
          val activeTitle = Option(dom.document.querySelector(".channel-item.active .channel-title"))
            .flatMap(node => Option(node.textContent))
            .map(_.trim.stripPrefix("#").trim)
          activeTitle.flatMap(title => chaptersVar.now().find(_.title == title).map(_.id))
        }
        .orElse(chaptersVar.now().headOption.map(_.id))
    }

    def saveMessagesToCache(chapterId: Long, messages: List[MessageResponse]): Unit = {
      cachedMessagesVar.update(_ + (chapterId -> messages))
      OfflineMessageStore.saveCachedMessages(auth.userId, chapterId, messages)
    }

    def loadMessagesFromCache(chapterId: Long): List[MessageResponse] = {
      val cached = cachedMessagesVar.now().getOrElse(chapterId, OfflineMessageStore.loadCachedMessages(auth.userId, chapterId))
      OfflineMessageStore.mergeCachedWithPending(cached, pendingOfflineMessageVar.now(), chapterId, auth.userId)
    }

    def queueOfflineMessage(chapterId: Long, content: String): Unit = {
      val trimmed = content.trim
      if trimmed.nonEmpty then {
        val now = nowEpochMillis()
        OfflineMessageStore.upsertSinglePending(pendingOfflineMessageVar.now(), chapterId, trimmed, now) match {
          case Right(pending) =>
            pendingOfflineMessageVar.set(Some(pending))
            OfflineMessageStore.savePending(auth.userId, pending)
            val offlineMessage = OfflineMessageStore.toOfflineMessage(pending, auth.userId)
            messagesVar.update { current =>
              val withoutPrev = current.filterNot(_.id == pending.tempId)
              offlineMessage :: withoutPrev
            }
            saveMessagesToCache(chapterId, messagesVar.now())
            statusVar.set("Offline: queued one message for sync")
          case Left(error) =>
            statusVar.set(error)
        }
      }
    }

    def clearPendingOfflineMessage(): Unit = {
      pendingOfflineMessageVar.set(None)
      OfflineMessageStore.clearPending(auth.userId)
    }

    def syncPendingOfflineMessage(chapterId: Long): Unit = {
      if !onlineNow() then return
      pendingOfflineMessageVar.now() match {
        case Some(pending) if pending.chapterId == chapterId =>
          BackendClient.createMessage(auth.sessionToken, pending.content, Some(pending.lastEditedAtEpochMillis)).foreach {
            case Right(created) =>
              BackendClient.addMessageToChapter(auth.sessionToken, chapterId, created.id).foreach {
                case Right(_) =>
                  clearPendingOfflineMessage()
                  messagesVar.update { current =>
                    created :: current.filterNot(_.id == pending.tempId)
                  }
                  autoReadMarkedIdsVar.update(_ + created.id)
                  saveMessagesToCache(chapterId, messagesVar.now())
                  refreshUnreadForChapter(chapterId)
                  statusVar.set(s"Synced offline message as #${created.id}")
                case Left(error) =>
                  statusVar.set(s"Offline sync failed: $error")
              }
            case Left(error) =>
              statusVar.set(s"Offline sync failed: $error")
          }
        case _ =>
          ()
      }
    }

    def setOnlineStatus(online: Boolean): Unit = {
      isOnlineVar.set(online)
      if online then selectedChapterIdVar.now().foreach(syncPendingOfflineMessage)
    }

    def recomputeFirstUnreadDividerForSelectedChapter(): Unit = {
      selectedChapterIdVar.now() match {
        case Some(chapterId) if isChapterViewVar.now() =>
          val unreadCount = unreadByChapterVar.now().get(chapterId).map(_.unreadCount).getOrElse(0)
          val items = messagesVar.now() // Newest-first
          val firstUnreadId =
            if unreadCount > 0 && unreadCount <= items.size then Some(items(unreadCount - 1).id)
            else None
          firstUnreadIdVar.set(firstUnreadId)
        case _ =>
          firstUnreadIdVar.set(None)
      }
    }

    def adjustUnreadCountOptimistically(chapterId: Long, delta: Int): Unit = {
      val nextCount = unreadByChapterVar.now().get(chapterId).map(state => math.max(0, state.unreadCount + delta)).getOrElse(0)
      setUnreadCountOptimistically(chapterId, nextCount)
    }

    def setUnreadCountOptimistically(chapterId: Long, unreadCount: Int): Unit = {
      unreadByChapterVar.update { current =>
        current.get(chapterId) match {
          case Some(state) =>
            current + (chapterId -> state.copy(unreadCount = math.max(0, unreadCount)))
          case None =>
            current + (chapterId -> ChapterUnreadCountResponse(chapterId, math.max(0, unreadCount), "none"))
        }
      }
      if selectedChapterIdVar.now().contains(chapterId) then {
        if unreadCount <= 0 then firstUnreadIdVar.set(None)
        recomputeFirstUnreadDividerForSelectedChapter()
      }
    }

    def isBlockedByUnreadBarrier(messageId: Long): Boolean = {
      autoReadBlockedFromMessageIdVar.now() match {
        case Some(blockFromId) =>
          val items = messagesVar.now() // Newest-first from API
          val barrierIndex = items.indexWhere(_.id == blockFromId)
          val messageIndex = items.indexWhere(_.id == messageId)
          if barrierIndex >= 0 && messageIndex >= 0 then messageIndex <= barrierIndex
          else messageId >= blockFromId
        case None =>
          false
      }
    }

    def evaluateVisibleMessagesForAutoRead(): Unit = {
      if !isChapterViewVar.now() then return
      messageListRef.now().foreach { listEl =>
        val containerRect = listEl.getBoundingClientRect()
        val newlySeen = messagesVar.now().flatMap { message =>
          Option(listEl.querySelector(s"#msg-${message.id}"))
            .map(_.asInstanceOf[dom.Element])
            .flatMap { node =>
              val r = node.getBoundingClientRect()
              val fullyVisible = r.top >= containerRect.top && r.bottom <= containerRect.bottom
              if fullyVisible then Some(message.id) else None
            }
        }.toSet

        if newlySeen.nonEmpty then {
          visibleFullySeenIdsVar.update(_ ++ newlySeen)
          val eligibleIds = (visibleFullySeenIdsVar.now() ++ newlySeen)
            .filterNot(id => autoReadMarkedIdsVar.now().contains(id))
            .filterNot(id => pendingAutoReadIdsVar.now().contains(id))
            .filterNot(id => isBlockedByUnreadBarrier(id))
          if eligibleIds.nonEmpty then pendingAutoReadIdsVar.update(_ ++ eligibleIds)
        }
      }
    }

    def flushPendingAutoReadOnInteraction(): Unit = {
      if autoReadInFlightVar.now() || !isChapterViewVar.now() then return
      selectedChapterIdVar.now().foreach { chapterId =>
        val pendingIds = pendingAutoReadIdsVar.now().toList
          .filterNot(id => isBlockedByUnreadBarrier(id))
        if pendingIds.nonEmpty then {
          autoReadInFlightVar.set(true)
          pendingAutoReadIdsVar.update(_ -- pendingIds)
          autoReadMarkedIdsVar.update(_ ++ pendingIds)
          adjustUnreadCountOptimistically(chapterId, -pendingIds.size)
          var remaining = pendingIds.size
          var successCount = 0
          pendingIds.foreach { messageId =>
            BackendClient.markMessageRead(auth.sessionToken, chapterId, messageId).foreach {
              case Right(_) =>
                successCount += 1
                remaining -= 1
                if remaining == 0 then {
                  autoReadInFlightVar.set(false)
                  if successCount > 0 then {
                    recomputeFirstUnreadDividerForSelectedChapter()
                    refreshUnreadForChapter(chapterId)
                    statusVar.set(s"Auto-marked $successCount messages as read")
                  }
                }
              case Left(_) =>
                autoReadMarkedIdsVar.update(_ - messageId)
                adjustUnreadCountOptimistically(chapterId, 1)
                pendingAutoReadIdsVar.update(_ + messageId)
                remaining -= 1
                if remaining == 0 then autoReadInFlightVar.set(false)
            }
          }
        }
      }
    }

    def refreshUnreadForChapter(chapterId: Long): Unit = {
      BackendClient.chapterUnreadCount(auth.sessionToken, chapterId).foreach {
        case Right(unread) =>
          unreadByChapterVar.update(_ + (chapterId -> unread))
          if selectedChapterIdVar.now().contains(chapterId) then recomputeFirstUnreadDividerForSelectedChapter()
        case Left(_) =>
          ()
      }
    }

    def refreshAllUnreadCounts(): Unit = {
      chaptersVar.now().foreach(ch => refreshUnreadForChapter(ch.id))
    }

    def loadChapterData(): Unit = {
      statusVar.set("Loading channels...")
      BackendClient.listChapters(auth.sessionToken).foreach {
        case Right(chapters) =>
          chaptersVar.set(chapters)
          if selectedChapterIdVar.now().isEmpty && chapters.nonEmpty then {
            selectChapter(chapters.head.id)
          }
          refreshAllUnreadCounts()
          statusVar.set(s"Loaded ${chapters.size} channels")
        case Left(error) =>
          statusVar.set(s"Failed to load channels: $error")
      }

      BackendClient.listChapterPreferences(auth.sessionToken).foreach {
        case Right(preferences) =>
          preferencesVar.set(preferences.map(pref => pref.chapterId -> pref).toMap)
        case Left(_) =>
          ()
      }
    }

    def updatePreference(chapterId: Long, isImportant: Boolean, muteLevel: String): Unit = {
      BackendClient.updateChapterPreference(auth.sessionToken, chapterId, isImportant, muteLevel).foreach {
        case Right(updated) =>
          preferencesVar.update(_ + (chapterId -> updated))
          refreshUnreadForChapter(chapterId)
          statusVar.set(s"Updated preferences for #$chapterId")
        case Left(error) =>
          statusVar.set(s"Preference update failed: $error")
      }
    }

    def runSearch(resetCursor: Boolean): Unit = {
      val query = searchVar.now().trim
      if query.isEmpty then {
        statusVar.set("Search query is required.")
      } else {
        val targetUserId = parseOptionalLong(searchTargetUserIdVar.now())
        val cursor = if resetCursor then None else searchCursorVar.now()
        BackendClient.searchMessages(auth.sessionToken, query, targetUserId, cursor, defaultPageSize).foreach {
          case Right(page) =>
            messagesVar.set(page.items)
            isChapterViewVar.set(false)
            firstUnreadIdVar.set(None)
            visibleFullySeenIdsVar.set(Set.empty)
            pendingAutoReadIdsVar.set(Set.empty)
            autoReadMarkedIdsVar.set(Set.empty)
            autoReadBlockedFromMessageIdVar.set(None)
            autoReadInFlightVar.set(false)
            searchNextCursorVar.set(page.nextCursor)
            searchCursorVar.set(cursor)
            statusVar.set(s"Found ${page.items.size} messages")
          case Left(error) =>
            statusVar.set(s"Search failed: $error")
        }
      }
    }

    def loadChapterTimeline(resetCursor: Boolean, scrollToFirstUnread: Boolean = false): Unit = {
      if isLoadingMoreVar.now() then return
      isLoadingMoreVar.set(true)
      selectedChapterIdVar.now() match {
        case Some(chapterId) =>
          if resetCursor then syncPendingOfflineMessage(chapterId)
          val cursor = if resetCursor then None else chapterCursorVar.now()
          BackendClient.listChapterMessages(auth.sessionToken, chapterId, cursor, defaultPageSize).foreach {
            case Right(page) =>
              if resetCursor then {
                messagesVar.set(page.items)
                saveMessagesToCache(chapterId, page.items)
                chapterNextCursorVar.set(page.nextCursor)
                chapterCursorVar.set(None)
                isChapterViewVar.set(true)
                recomputeFirstUnreadDividerForSelectedChapter()
                val firstUnreadId = firstUnreadIdVar.now().filter(_ => scrollToFirstUnread)
                firstUnreadIdVar.set(firstUnreadId)
                isLoadingMoreVar.set(false)
                statusVar.set(s"Loaded ${page.items.size} chapter messages")
                timers.setTimeout(50.0) {
                  messageListRef.now().foreach { el =>
                    firstUnreadId match {
                      case Some(id) =>
                        Option(el.querySelector(s"#msg-$id")).foreach { target =>
                          target.asInstanceOf[js.Dynamic].scrollIntoView(true)
                        }
                      case None =>
                        el.scrollTop = el.scrollHeight.toDouble
                    }
                  }
                  evaluateVisibleMessagesForAutoRead()
                }
              } else {
                val prevScrollHeight = messageListRef.now().map(_.scrollHeight).getOrElse(0)
                val prevScrollTop    = messageListRef.now().map(_.scrollTop).getOrElse(0.0)
                val updatedItems     = messagesVar.now() ++ page.items
                messagesVar.set(updatedItems)
                saveMessagesToCache(chapterId, updatedItems)
                chapterNextCursorVar.set(page.nextCursor)
                chapterCursorVar.set(cursor)
                isLoadingMoreVar.set(false)
                statusVar.set(s"Loaded ${page.items.size} older messages")
                timers.setTimeout(50.0) {
                  messageListRef.now().foreach { el =>
                    el.scrollTop = prevScrollTop + (el.scrollHeight - prevScrollHeight)
                  }
                  evaluateVisibleMessagesForAutoRead()
                }
              }
            case Left(error) =>
              if resetCursor then {
                val cached = loadMessagesFromCache(chapterId)
                if cached.nonEmpty then {
                  messagesVar.set(cached)
                  chapterNextCursorVar.set(None)
                  chapterCursorVar.set(None)
                  isChapterViewVar.set(true)
                  recomputeFirstUnreadDividerForSelectedChapter()
                  statusVar.set("Offline: loaded cached messages")
                } else {
                  statusVar.set(s"Timeline failed: $error")
                }
              } else {
                statusVar.set(s"Timeline failed: $error")
              }
              isLoadingMoreVar.set(false)
          }
        case None =>
          isLoadingMoreVar.set(false)
          statusVar.set("Select a chapter first")
      }
    }

    def createMessage(): Unit = {
      val content = newMessageVar.now().trim
      if content.isEmpty then {
        statusVar.set("Message cannot be empty")
      } else {
        if !onlineNow() then {
          resolveCurrentChapterIdForOfflineQueue() match {
            case Some(chapterId) =>
              queueOfflineMessage(chapterId, content)
              newMessageVar.set("")
            case None =>
              statusVar.set("Select a chapter first")
          }
          return
        }
        val clientEditedAt = Some(nowEpochMillis())
        BackendClient.createMessage(auth.sessionToken, content, clientEditedAt).foreach {
          case Right(created) =>
            newMessageVar.set("")
            selectedMessageVar.set(Some(created))
            selectedMessageIdVar.set(created.id.toString)
            editContentVar.set(created.content)
            selectedChapterIdVar.now().foreach(chapterId => {
              BackendClient.addMessageToChapter(auth.sessionToken, chapterId, created.id).foreach {
                case Right(_) =>
                  // Optimistically show the message in-place so the sender always sees what they just sent.
                  messagesVar.update { current =>
                    if current.exists(_.id == created.id) then current else created :: current
                  }
                  autoReadMarkedIdsVar.update(_ + created.id)
                  pendingAutoReadIdsVar.update(_ - created.id)
                  recomputeFirstUnreadDividerForSelectedChapter()
                  saveMessagesToCache(chapterId, messagesVar.now())
                  timers.setTimeout(0.0) {
                    messageListRef.now().foreach(el => el.scrollTop = el.scrollHeight.toDouble)
                  }
                  // Ensure backend read state includes the newly posted message for the posting user.
                  BackendClient.markMessageRead(auth.sessionToken, chapterId, created.id).foreach {
                    case Right(_) =>
                      refreshUnreadForChapter(chapterId)
                      statusVar.set(s"Message #${created.id} sent")
                    case Left(_) =>
                      refreshUnreadForChapter(chapterId)
                      statusVar.set(s"Message #${created.id} sent")
                  }
                case Left(error) =>
                  statusVar.set(s"Failed to add message #${created.id} to chapter: $error")
              }
            })
            if selectedChapterIdVar.now().isEmpty then runSearch(resetCursor = true)
          case Left(error) =>
            if error.toLowerCase.contains("network error") || !onlineNow() then {
              val fallbackChapterId = resolveCurrentChapterIdForOfflineQueue()
              fallbackChapterId match {
                case Some(chapterId) =>
                  queueOfflineMessage(chapterId, content)
                  newMessageVar.set("")
                case None =>
                  statusVar.set(s"Create failed: $error")
              }
            } else {
              statusVar.set(s"Create failed: $error")
            }
        }
      }
    }

    def openMessageById(): Unit = {
      parseOptionalLong(selectedMessageIdVar.now()) match {
        case Some(messageId) =>
          if messageId < 0 then {
            messagesVar.now().find(_.id == messageId) match {
              case Some(message) =>
                selectedMessageVar.set(Some(message))
                editContentVar.set(message.content)
                historyVar.set(Nil)
                statusVar.set(s"Opened offline message #${message.id}")
              case None =>
                statusVar.set(s"Offline message #$messageId not found")
            }
          } else {
            BackendClient.getMessageById(auth.sessionToken, messageId).foreach {
              case Right(message) =>
                selectedMessageVar.set(Some(message))
                editContentVar.set(message.content)
                loadHistory(message.id)
                statusVar.set(s"Opened message #${message.id}")
              case Left(error) =>
                statusVar.set(s"Open failed: $error")
            }
          }
        case None =>
          statusVar.set("Enter a valid message id")
      }
    }

    def loadHistory(messageId: Long): Unit = {
      BackendClient.messageHistory(auth.sessionToken, messageId).foreach {
        case Right(entries) => historyVar.set(entries)
        case Left(error) => statusVar.set(s"History failed: $error")
      }
    }

    def editSelectedMessage(): Unit = {
      (selectedMessageVar.now(), editContentVar.now().trim) match {
        case (Some(message), updated) if updated.nonEmpty =>
          if message.id < 0 then {
            pendingOfflineMessageVar.now() match {
              case Some(pending) if pending.tempId == message.id =>
                val updatedPending = pending.copy(content = updated, lastEditedAtEpochMillis = nowEpochMillis())
                pendingOfflineMessageVar.set(Some(updatedPending))
                OfflineMessageStore.savePending(auth.userId, updatedPending)
                val offlineMessage = OfflineMessageStore.toOfflineMessage(updatedPending, auth.userId)
                messagesVar.update(_.map(m => if m.id == message.id then offlineMessage else m))
                selectedMessageVar.set(Some(offlineMessage))
                editContentVar.set(updated)
                statusVar.set("Updated offline pending message")
              case _ =>
                statusVar.set("Offline pending message not found")
            }
          } else {
            BackendClient.editMessage(
              auth.sessionToken,
              message.id,
              updated,
              expectedVersion = Some(message.version),
              clientEditedAtEpochMillis = Some(nowEpochMillis())
            ).foreach {
              case Right(value) =>
                selectedMessageVar.set(Some(value))
                editContentVar.set(value.content)
                loadHistory(value.id)
                if selectedChapterIdVar.now().isDefined then {
                  messagesVar.update(_.map(m => if m.id == value.id then value else m))
                  selectedChapterIdVar.now().foreach { ch =>
                    saveMessagesToCache(ch, messagesVar.now())
                    refreshUnreadForChapter(ch)
                  }
                  recomputeFirstUnreadDividerForSelectedChapter()
                } else runSearch(resetCursor = true)
                statusVar.set(s"Updated message #${value.id}")
              case Left(error) =>
                statusVar.set(s"Edit failed: $error")
            }
          }
        case _ =>
          statusVar.set("Select a message and type updated content")
      }
    }

    def deleteSelectedMessage(): Unit = {
      selectedMessageVar.now() match {
        case Some(message) =>
          BackendClient.deleteMessage(auth.sessionToken, message.id).foreach {
            case Right(_) =>
              if selectedChapterIdVar.now().isDefined then loadChapterTimeline(resetCursor = true)
              else runSearch(resetCursor = true)
              statusVar.set(s"Deleted message #${message.id}")
            case Left(error) =>
              statusVar.set(s"Delete failed: $error")
          }
        case None =>
          statusVar.set("Select a message first")
      }
    }

    def loadAuditEntries(resetCursor: Boolean): Unit = {
      val targetUserId = parseOptionalLong(auditTargetUserVar.now())
      val cursor = if resetCursor then None else auditCursorVar.now()
      BackendClient.listAuditEntries(auth.sessionToken, targetUserId, None, cursor, defaultPageSize).foreach {
        case Right(page) =>
          auditEntriesVar.set(page.items)
          auditNextCursorVar.set(page.nextCursor)
          auditCursorVar.set(cursor)
          statusVar.set(s"Loaded ${page.items.size} audit entries")
        case Left(error) =>
          statusVar.set(s"Audit failed: $error")
      }
    }

    def nextSearchPage(): Unit = {
      if searchVar.now().trim.nonEmpty then {
        searchNextCursorVar.now() match {
          case Some(cursor) =>
            searchCursorVar.set(Some(cursor))
            runSearch(resetCursor = false)
          case None =>
            statusVar.set("No more search results")
        }
      } else {
        chapterNextCursorVar.now() match {
          case Some(cursor) =>
            chapterCursorVar.set(Some(cursor))
            loadChapterTimeline(resetCursor = false)
          case None =>
            statusVar.set("No more chapter messages")
        }
      }
    }

    def nextAuditPage(): Unit = {
      auditNextCursorVar.now() match {
        case Some(cursor) =>
          auditCursorVar.set(Some(cursor))
          loadAuditEntries(resetCursor = false)
        case None =>
          statusVar.set("No more audit entries")
      }
    }

    def selectChapter(chapterId: Long): Unit = {
      selectedChapterIdVar.set(Some(chapterId))
      lastSelectedChapterIdVar.set(Some(chapterId))
      searchVar.set("")
      searchCursorVar.set(None)
      searchNextCursorVar.set(None)
      chapterCursorVar.set(None)
      firstUnreadIdVar.set(None)
      isLoadingMoreVar.set(false)
      visibleFullySeenIdsVar.set(Set.empty)
      pendingAutoReadIdsVar.set(Set.empty)
      autoReadMarkedIdsVar.set(Set.empty)
      autoReadBlockedFromMessageIdVar.set(None)
      autoReadInFlightVar.set(false)
      if !onlineNow() then {
        val cached = loadMessagesFromCache(chapterId)
        messagesVar.set(cached)
        isChapterViewVar.set(true)
        chapterNextCursorVar.set(None)
        chapterCursorVar.set(None)
        recomputeFirstUnreadDividerForSelectedChapter()
        statusVar.set(if cached.nonEmpty then "Offline: loaded cached messages" else "Offline: no cached messages for this chapter")
        return
      }
      // Load unread count first so we can scroll to the first unread message
      BackendClient.chapterUnreadCount(auth.sessionToken, chapterId).foreach {
        case Right(unread) =>
          unreadByChapterVar.update(_ + (chapterId -> unread))
          loadChapterTimeline(resetCursor = true, scrollToFirstUnread = true)
        case Left(_) =>
          loadChapterTimeline(resetCursor = true, scrollToFirstUnread = false)
      }
    }

    def markSelectedMessageRead(): Unit = {
      for {
        chapterId <- selectedChapterIdVar.now()
        message <- selectedMessageVar.now()
      } {
        BackendClient.markMessageRead(auth.sessionToken, chapterId, message.id).foreach {
          case Right(_) =>
            autoReadMarkedIdsVar.update(_ + message.id)
            pendingAutoReadIdsVar.update(_ - message.id)
            adjustUnreadCountOptimistically(chapterId, -1)
            refreshUnreadForChapter(chapterId)
            statusVar.set(s"Marked message #${message.id} as read")
          case Left(error) =>
            statusVar.set(s"Mark read failed: $error")
        }
      }
    }

    def markUnreadFromSelectedMessage(): Unit = {
      for {
        chapterId <- selectedChapterIdVar.now()
        message <- selectedMessageVar.now()
      } {
        val blockedIds = messagesVar.now().takeWhile(_.id != message.id).map(_.id).toSet + message.id
        // Set barrier immediately so the same click cannot trigger auto-read before server ack.
        autoReadBlockedFromMessageIdVar.set(Some(message.id))
        pendingAutoReadIdsVar.update(_ -- blockedIds)
        autoReadMarkedIdsVar.update(_ -- blockedIds)
        setUnreadCountOptimistically(chapterId, blockedIds.size)
        BackendClient.markUnreadFrom(auth.sessionToken, chapterId, message.id).foreach {
          case Right(_) =>
            refreshUnreadForChapter(chapterId)
            statusVar.set(s"Marked message #${message.id} and newer as unread")
          case Left(error) =>
            refreshUnreadForChapter(chapterId)
            statusVar.set(s"Mark unread failed: $error")
        }
      }
    }

    dom.window.addEventListener("online", (_: dom.Event) => setOnlineStatus(true))
    dom.window.addEventListener("offline", (_: dom.Event) => setOnlineStatus(false))
    setOnlineStatus(dom.window.navigator.onLine)

    loadChapterData()

    div(
      cls := "chat-layout",
      onClick.mapTo(()) --> (_ => flushPendingAutoReadOnInteraction()),
      onKeyDown.mapTo(()) --> (_ => flushPendingAutoReadOnInteraction()),
      div(
        cls := "chat-channels",
        div(
          cls := "panel-head",
          h3("Channels"),
          button("Refresh", onClick.mapTo(()) --> (_ => loadChapterData()))
        ),
        div(
          cls := "channel-list",
          children <-- Signal.combine(chaptersVar.signal, preferencesVar.signal, unreadByChapterVar.signal, selectedChapterIdVar.signal).map {
            case (chapters, prefs, unread, selectedOpt) =>
              if chapters.isEmpty then {
                List(p(cls := "hint", "No chapters found yet."))
              } else {
                chapters.map { chapter =>
                  val chapterPref = prefs.get(chapter.id)
                  val unreadCount = unread.get(chapter.id).map(_.unreadCount).getOrElse(0)
                  val muteLevel = chapterPref.map(_.muteLevel).getOrElse("none")
                  button(
                    cls := "channel-item",
                    cls("active") := selectedOpt.contains(chapter.id),
                    div(
                      cls := "channel-item-main",
                      span(cls := "channel-title", s"# ${chapter.title}"),
                      if unreadCount > 0 then span(cls := "channel-unread", unreadCount.toString) else span()
                    ),
                    div(
                      cls := "channel-item-meta",
                      if chapterPref.exists(_.isImportant) then span("important") else span("normal"),
                      span(muteLevel)
                    ),
                    onClick.mapTo(chapter.id) --> (id => selectChapter(id))
                  )
                }
              }
          }
        )
      ),
      div(
        cls := "chat-thread",
        div(
          cls := "panel-head",
          child.text <-- Signal.combine(chaptersVar.signal, selectedChapterIdVar.signal).map {
            case (chapters, Some(id)) =>
              chapters.find(_.id == id).map(ch => s"# ${ch.title}").getOrElse("Direct Messages")
            case _ =>
              "Direct Messages"
          },
          div(
            cls := "head-actions",
            child <-- selectedChapterIdVar.signal.map {
              case Some(chapterId) =>
                val prefSignal = preferencesVar.signal.map(_.get(chapterId))
                div(
                  button(
                    child.text <-- prefSignal.map {
                      case Some(pref) if pref.isImportant => "Unmark Important"
                      case _ => "Mark Important"
                    },
                    onClick.mapTo(chapterId) --> { id =>
                      val current = preferencesVar.now().get(id)
                      val nextImportant = !current.exists(_.isImportant)
                      val nextMute = current.map(_.muteLevel).getOrElse("none")
                      updatePreference(id, nextImportant, nextMute)
                    }
                  ),
                  button("Mute", onClick.mapTo(chapterId) --> (id => {
                    val current = preferencesVar.now().get(id)
                    updatePreference(id, current.exists(_.isImportant), "hard")
                  })),
                  button("Soft Mute", onClick.mapTo(chapterId) --> (id => {
                    val current = preferencesVar.now().get(id)
                    updatePreference(id, current.exists(_.isImportant), "soft")
                  })),
                  button("Unmute", onClick.mapTo(chapterId) --> (id => {
                    val current = preferencesVar.now().get(id)
                    updatePreference(id, current.exists(_.isImportant), "none")
                  }))
                )
              case None =>
                span()
            }
          )
        ),
        div(
          cls := "composer-row",
          textArea(
            cls := "composer-input",
            rows := 3,
            placeholder := "Write a message, then Enter Send",
            controlled(
              value <-- newMessageVar.signal,
              onInput.mapToValue --> newMessageVar.writer
            )
          ),
          div(
            cls := "composer-actions",
            button("Send", onClick.mapTo(()) --> (_ => createMessage())),
            input(
              cls := "search-input",
              placeholder := "Search in conversation",
              controlled(value <-- searchVar.signal, onInput.mapToValue --> searchVar.writer)
            ),
            input(
              cls := "search-input compact",
              placeholder := "Target user (admin)",
              controlled(value <-- searchTargetUserIdVar.signal, onInput.mapToValue --> searchTargetUserIdVar.writer)
            ),
            button("Search", onClick.mapTo(()) --> (_ => {
              searchCursorVar.set(None)
              if searchVar.now().trim.nonEmpty then runSearch(resetCursor = true)
              else {
                chapterCursorVar.set(None)
                loadChapterTimeline(resetCursor = true)
              }
            })),
            button("More", onClick.mapTo(()) --> (_ => nextSearchPage()))
          )
        ),
        div(
          cls := "message-list",
          onMountCallback(ctx => messageListRef.set(Some(ctx.thisNode.ref))),
          onScroll --> { _ =>
            evaluateVisibleMessagesForAutoRead()
            messageListRef.now().foreach { el =>
              if el.scrollTop < 80
                 && isChapterViewVar.now()
                 && chapterNextCursorVar.now().isDefined
                 && !isLoadingMoreVar.now() then {
                chapterCursorVar.set(chapterNextCursorVar.now())
                loadChapterTimeline(resetCursor = false)
              }
            }
          },
          children <-- Signal.combine(
            messagesVar.signal,
            firstUnreadIdVar.signal,
            isChapterViewVar.signal,
            unreadByChapterVar.signal,
            selectedChapterIdVar.signal
          ).map {
            case (Nil, _, _, _, _) =>
              List(
                div(cls := "message-empty", "No messages yet. Send a message or run a search.")
              )
            case (items, firstUnreadId, isChapterView, unreadByChapter, selectedChapterId) =>
              val unreadCount = selectedChapterId.flatMap(id => unreadByChapter.get(id).map(_.unreadCount)).getOrElse(0)
              val activeFirstUnreadId = if unreadCount > 0 then firstUnreadId else None
              val displayItems = if isChapterView then items.reverse else items
              displayItems.map { message =>
                val isFirstUnread = isChapterView && activeFirstUnreadId.contains(message.id)
                val timestamp = new js.Date(message.updatedAtEpochMillis.toDouble).toLocaleString()
                val bubble: HtmlElement = div(
                  cls := "message-bubble",
                  cls("deleted") := message.deleted,
                  idAttr := s"msg-${message.id}",
                  div(
                    cls := "message-header",
                    button(
                      cls := "message-link",
                      s"#${message.id}",
                      onClick.mapTo(message.id.toString) --> { idText =>
                        selectedMessageIdVar.set(idText)
                        openMessageById()
                      }
                    ),
                    span(cls := "message-author", s"user ${message.authorUserId}"),
                    span(cls := "message-time", timestamp)
                  ),
                  p(cls := "message-content", message.content),
                  if message.deleted then span(cls := "message-tag", "deleted") else span()
                )
                div(
                  cls := "message-entry",
                  if isFirstUnread then div(cls := "unread-divider", span("New messages")) else emptyNode,
                  bubble
                )
              }
          }
        )
      ),
      div(
        cls := "chat-sidepanel",
        div(
          cls := "panel-head",
          h3("Message Inspector")
        ),
        div(
          cls := "inspector-open",
          input(
            placeholder := "Message id",
            controlled(value <-- selectedMessageIdVar.signal, onInput.mapToValue --> selectedMessageIdVar.writer)
          ),
          button("Open", onClick.mapTo(()) --> (_ => openMessageById()))
        ),
        child <-- selectedMessageVar.signal.map {
          case Some(message) =>
            div(
              cls := "inspector-card",
              p(s"Deep link: ${message.deepLink}"),
              p(s"Created: ${new js.Date(message.createdAtEpochMillis.toDouble).toISOString()}"),
              p(s"Updated: ${new js.Date(message.updatedAtEpochMillis.toDouble).toISOString()}"),
              textArea(
                rows := 3,
                controlled(value <-- editContentVar.signal, onInput.mapToValue --> editContentVar.writer)
              ),
              div(
                cls := "stack-actions",
                button("Save Edit", onClick.mapTo(()) --> (_ => editSelectedMessage())),
                button("Delete", onClick.mapTo(()) --> (_ => deleteSelectedMessage())),
                button("Mark Read", onClick.mapTo(()) --> (_ => markSelectedMessageRead())),
                button("Unread From Here", onClick.stopPropagation.mapTo(()) --> (_ => markUnreadFromSelectedMessage()))
              )
            )
          case None =>
            div(cls := "hint", "Select a message to inspect and edit.")
        },
        div(
          cls := "history-box",
          h4("Edit History"),
          ul(
            children <-- historyVar.signal.map(_.map { entry =>
              li(
                s"v${entry.version} by ${entry.editedByUserId} at ${new js.Date(entry.editedAtEpochMillis.toDouble).toLocaleString()}"
              )
            })
          )
        ),
        div(
          cls := "audit-box",
          h4("Audit Stream"),
          input(
            placeholder := "Target user id",
            controlled(value <-- auditTargetUserVar.signal, onInput.mapToValue --> auditTargetUserVar.writer)
          ),
          div(
            cls := "stack-actions",
            button("Load", onClick.mapTo(()) --> (_ => {
              auditCursorVar.set(None)
              loadAuditEntries(resetCursor = true)
            })),
            button("More", onClick.mapTo(()) --> (_ => nextAuditPage()))
          ),
          ul(
            children <-- auditEntriesVar.signal.map(_.map { entry =>
              li(s"${entry.action} actor=${entry.actorUserId} target=${entry.targetUserId.getOrElse(-1L)}")
            })
          )
        ),
        p(cls := "status-line", child.text <-- statusVar.signal)
      )
    )
  }
}
