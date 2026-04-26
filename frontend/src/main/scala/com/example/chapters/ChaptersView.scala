package com.example.chapters

import com.example.api.*
import com.raquo.laminar.api.L.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object ChaptersView {

  def view(auth: AuthResponse): HtmlElement = {
    val statusVar        = Var("")
    val chaptersVar      = Var(List.empty[ChapterResponse])
    val selectedVar      = Var(Option.empty[ChapterDetailResponse])
    val newTitleVar      = Var("")
    val newParentIdVar   = Var("")
    val addMemberIdVar   = Var("")
    val addMemberRoleVar = Var("viewer")
    val addMsgIdVar      = Var("")
    val shareLinkVar     = Var("")

    def setStatus(s: String): Unit = statusVar.set(s)

    def loadChapters(): Unit = {
      setStatus("Loading chapters...")
      BackendClient.listChapters(auth.sessionToken).foreach {
        case Right(list) =>
          chaptersVar.set(list)
          setStatus(s"Loaded ${list.size} chapters")
        case Left(err) =>
          setStatus(s"Failed to load chapters: $err")
      }
    }

    def openChapter(id: Long): Unit = {
      setStatus("Loading chapter details...")
      shareLinkVar.set("")
      BackendClient.getChapter(auth.sessionToken, id).foreach {
        case Right(detail) =>
          selectedVar.set(Some(detail))
          setStatus(s"Opened chapter #$id")
        case Left(err) =>
          setStatus(s"Failed to load chapter: $err")
      }
    }

    def createChapter(): Unit = {
      val title = newTitleVar.now().trim
      if title.isEmpty then setStatus("Title is required.")
      else {
        val parentId = newParentIdVar.now().trim match {
          case s if s.nonEmpty => scala.util.Try(s.toLong).toOption
          case _               => None
        }
        BackendClient.createChapter(auth.sessionToken, title, parentId).foreach {
          case Right(created) =>
            newTitleVar.set("")
            newParentIdVar.set("")
            setStatus(s"Created chapter #${created.id}")
            loadChapters()
            openChapter(created.id)
          case Left(err) =>
            setStatus(s"Failed to create chapter: $err")
        }
      }
    }

    def deleteSelected(): Unit = {
      selectedVar.now().foreach { detail =>
        val id = detail.chapter.id
        BackendClient.deleteChapter(auth.sessionToken, id).foreach {
          case Right(_) =>
            selectedVar.set(None)
            setStatus(s"Deleted chapter #$id")
            loadChapters()
          case Left(err) =>
            setStatus(s"Failed to delete chapter: $err")
        }
      }
    }

    def setVisibility(vis: String): Unit = {
      selectedVar.now().foreach { detail =>
        BackendClient.updateChapterVisibility(auth.sessionToken, detail.chapter.id, vis).foreach {
          case Right(_) =>
            setStatus(s"Visibility set to $vis")
            openChapter(detail.chapter.id)
            loadChapters()
          case Left(err) =>
            setStatus(s"Failed to update visibility: $err")
        }
      }
    }

    def addMember(): Unit = {
      selectedVar.now().foreach { detail =>
        scala.util.Try(addMemberIdVar.now().trim.toLong).toOption match {
          case None =>
            setStatus("Enter a valid numeric user id.")
          case Some(uid) =>
            BackendClient.addChapterMember(auth.sessionToken, detail.chapter.id, uid, addMemberRoleVar.now()).foreach {
              case Right(_) =>
                addMemberIdVar.set("")
                setStatus(s"Added member $uid")
                openChapter(detail.chapter.id)
              case Left(err) =>
                setStatus(s"Failed to add member: $err")
            }
        }
      }
    }

    def removeMember(userId: Long): Unit = {
      selectedVar.now().foreach { detail =>
        BackendClient.removeChapterMember(auth.sessionToken, detail.chapter.id, userId).foreach {
          case Right(_) =>
            setStatus(s"Removed member $userId")
            openChapter(detail.chapter.id)
          case Left(err) =>
            setStatus(s"Failed to remove member: $err")
        }
      }
    }

    def addMessage(): Unit = {
      selectedVar.now().foreach { detail =>
        scala.util.Try(addMsgIdVar.now().trim.toLong).toOption match {
          case None =>
            setStatus("Enter a valid numeric message id.")
          case Some(mid) =>
            BackendClient.addMessageToChapter(auth.sessionToken, detail.chapter.id, mid).foreach {
              case Right(_) =>
                addMsgIdVar.set("")
                setStatus(s"Added message $mid")
                openChapter(detail.chapter.id)
              case Left(err) =>
                setStatus(s"Failed to add message: $err")
            }
        }
      }
    }

    def removeMessage(messageId: Long): Unit = {
      selectedVar.now().foreach { detail =>
        BackendClient.removeMessageFromChapter(auth.sessionToken, detail.chapter.id, messageId).foreach {
          case Right(_) =>
            setStatus(s"Removed message $messageId")
            openChapter(detail.chapter.id)
          case Left(err) =>
            setStatus(s"Failed to remove message: $err")
        }
      }
    }

    def generateShareLink(): Unit = {
      selectedVar.now().foreach { detail =>
        BackendClient.createChapterShareLink(auth.sessionToken, detail.chapter.id).foreach {
          case Right(resp) =>
            shareLinkVar.set(resp.token)
            setStatus("Share link generated")
          case Left(err) =>
            setStatus(s"Failed to generate share link: $err")
        }
      }
    }

    loadChapters()

    div(
      cls := "management-layout",
      div(
        cls := "panel-card",
        div(
          cls := "panel-head",
          h3("Chapter Studio"),
          button("Refresh", onClick.mapTo(()) --> (_ => loadChapters()))
        ),
        div(
          cls := "form-stack",
          h4("Create chapter"),
          input(
            typ := "text",
            placeholder := "Chapter title",
            controlled(value <-- newTitleVar.signal, onInput.mapToValue --> newTitleVar.writer)
          ),
          input(
            typ := "text",
            placeholder := "Parent chapter id (optional)",
            controlled(value <-- newParentIdVar.signal, onInput.mapToValue --> newParentIdVar.writer)
          ),
          button("Create Chapter", onClick.mapTo(()) --> (_ => createChapter()))
        ),
        div(
          cls := "entity-list",
          children <-- chaptersVar.signal.map {
            case Nil =>
              List(p(cls := "hint", "No chapters yet."))
            case chapters =>
              chapters.map { ch =>
                button(
                  cls := "entity-row",
                  div(
                    cls := "entity-title-row",
                    strong(ch.title),
                    span(cls := "entity-badge", ch.visibility)
                  ),
                  span(cls := "entity-meta", s"id=${ch.id} owner=${ch.ownerUserId}"),
                  ch.parentChapterId.map(pid => span(cls := "entity-meta", s"parent=$pid")).getOrElse(span()),
                  onClick.mapTo(ch.id) --> (id => openChapter(id))
                )
              }
          }
        )
      ),
      div(
        cls := "panel-card",
        child <-- selectedVar.signal.map {
          case None =>
            div(
              cls := "empty-state",
              h3("Open a chapter"),
              p("Select a chapter from the left to manage visibility, members, messages, and share links.")
            )
          case Some(detail) =>
            val ch = detail.chapter
            val isOwner = ch.ownerUserId == auth.userId
            div(
              div(
                cls := "panel-head",
                div(
                  h3(ch.title),
                  p(cls := "entity-meta", s"chapter #${ch.id} • owner ${ch.ownerUserId}")
                ),
                if isOwner then button("Delete Chapter", onClick.mapTo(()) --> (_ => deleteSelected())) else span()
              ),
              div(
                cls := "detail-grid",
                div(
                  cls := "detail-card",
                  h4("Visibility"),
                  p(s"Current: ${ch.visibility}"),
                  if isOwner then div(
                    cls := "pill-actions",
                    button("Private", onClick.mapTo("private") --> (v => setVisibility(v))),
                    button("Individuals", onClick.mapTo("individuals") --> (v => setVisibility(v))),
                    button("Authenticated", onClick.mapTo("authenticated") --> (v => setVisibility(v))),
                    button("Group", onClick.mapTo("group") --> (v => setVisibility(v))),
                    button("Public", onClick.mapTo("public") --> (v => setVisibility(v)))
                  ) else p(cls := "hint", "Only the owner can change visibility.")
                ),
                div(
                  cls := "detail-card",
                  h4("Share Link"),
                  if isOwner then div(
                    button("Generate", onClick.mapTo(()) --> (_ => generateShareLink())),
                    child <-- shareLinkVar.signal.map { token =>
                      if token.isEmpty then p(cls := "hint", "No share link generated yet.")
                      else p(s"/share/$token")
                    }
                  ) else p(cls := "hint", "Only the owner can create share links.")
                )
              ),
              div(
                cls := "detail-grid",
                div(
                  cls := "detail-card",
                  h4("Members"),
                  if detail.members.isEmpty then p(cls := "hint", "No members yet.")
                  else ul(
                    detail.members.map { m =>
                      li(
                        span(s"User ${m.userId} • ${m.role}"),
                        if isOwner then button("Remove", onClick.mapTo(m.userId) --> (uid => removeMember(uid))) else span()
                      )
                    }
                  ),
                  if isOwner then div(
                    cls := "inline-form",
                    input(
                      typ := "text",
                      placeholder := "User id",
                      controlled(value <-- addMemberIdVar.signal, onInput.mapToValue --> addMemberIdVar.writer)
                    ),
                    select(
                      controlled(value <-- addMemberRoleVar.signal, onChange.mapToValue --> addMemberRoleVar.writer),
                      option(value := "viewer", "viewer"),
                      option(value := "editor", "editor")
                    ),
                    button("Add Member", onClick.mapTo(()) --> (_ => addMember()))
                  ) else span()
                ),
                div(
                  cls := "detail-card",
                  h4("Messages In Chapter"),
                  if detail.messageIds.isEmpty then p(cls := "hint", "No mapped messages yet.")
                  else ul(
                    detail.messageIds.map { mid =>
                      li(
                        span(s"Message $mid"),
                        if isOwner then button("Remove", onClick.mapTo(mid) --> (id => removeMessage(id))) else span()
                      )
                    }
                  ),
                  if isOwner then div(
                    cls := "inline-form",
                    input(
                      typ := "text",
                      placeholder := "Message id",
                      controlled(value <-- addMsgIdVar.signal, onInput.mapToValue --> addMsgIdVar.writer)
                    ),
                    button("Add Message", onClick.mapTo(()) --> (_ => addMessage()))
                  ) else span()
                )
              )
            )
        }
      ),
      p(cls := "status-line management-status", child.text <-- statusVar.signal)
    )
  }
}
