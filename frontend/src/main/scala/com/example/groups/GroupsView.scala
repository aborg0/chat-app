package com.example.groups

import com.example.api.{AuthResponse, BackendClient, GroupResponse}
import com.raquo.laminar.api.L.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object GroupsView {

  def view(auth: AuthResponse): HtmlElement = {
    val groupsVar  = Var(List.empty[GroupResponse])
    val errorVar   = Var("")
    val newNameVar = Var("")

    val selectedGroupVar = Var(Option.empty[GroupResponse])
    val membersVar       = Var(List.empty[Long])
    val newMemberIdVar   = Var("")
    val memberErrorVar   = Var("")

    def loadGroups(): Unit = {
      BackendClient.listGroups(auth.sessionToken).foreach {
        case Right(gs) =>
          groupsVar.set(gs)
          errorVar.set(s"Loaded ${gs.size} groups")
        case Left(err) =>
          errorVar.set(s"Error loading groups: $err")
      }
    }

    def loadMembers(groupId: Long): Unit = {
      BackendClient.listGroupMembers(auth.sessionToken, groupId).foreach {
        case Right(r) =>
          membersVar.set(r.memberUserIds)
          memberErrorVar.set("")
        case Left(err) =>
          memberErrorVar.set(s"Error loading members: $err")
      }
    }

    loadGroups()

    div(
      cls := "management-layout",
      div(
        cls := "panel-card",
        div(
          cls := "panel-head",
          h3("Group Directory"),
          button("Refresh", onClick.mapTo(()) --> (_ => loadGroups()))
        ),
        div(
          cls := "form-stack",
          h4("Create group"),
          input(
            typ := "text",
            placeholder := "Group name",
            controlled(value <-- newNameVar.signal, onInput.mapToValue --> newNameVar.writer)
          ),
          button(
            "Create Group",
            onClick.mapTo(()) --> { _ =>
              val name = newNameVar.now().trim
              if name.nonEmpty then {
                BackendClient.createGroup(auth.sessionToken, name).foreach {
                  case Right(created) =>
                    newNameVar.set("")
                    selectedGroupVar.set(Some(created))
                    errorVar.set(s"Created group #${created.id}")
                    loadGroups()
                    loadMembers(created.id)
                  case Left(err) =>
                    errorVar.set(s"Create failed: $err")
                }
              } else {
                errorVar.set("Group name is required")
              }
            }
          )
        ),
        div(
          cls := "entity-list",
          children <-- groupsVar.signal.map {
            case Nil =>
              List(p(cls := "hint", "No groups yet."))
            case groups =>
              groups.map { g =>
                button(
                  cls := "entity-row",
                  div(
                    cls := "entity-title-row",
                    strong(g.name),
                    if auth.userId == g.ownerUserId then span(cls := "entity-badge", "owner") else span(cls := "entity-badge", "member")
                  ),
                  span(cls := "entity-meta", s"id=${g.id} owner=${g.ownerUserId}"),
                  onClick.mapTo(()) --> { _ =>
                    selectedGroupVar.set(Some(g))
                    memberErrorVar.set("")
                    loadMembers(g.id)
                  }
                )
              }
          }
        )
      ),
      div(
        cls := "panel-card",
        child <-- selectedGroupVar.signal.map {
          case None =>
            div(
              cls := "empty-state",
              h3("Open a group"),
              p("Select a group to manage members or create a new one from the left.")
            )
          case Some(g) =>
            val isOwner = auth.userId == g.ownerUserId
            div(
              div(
                cls := "panel-head",
                div(
                  h3(g.name),
                  p(cls := "entity-meta", s"group #${g.id} • owner ${g.ownerUserId}")
                ),
                div(
                  cls := "head-actions",
                  if isOwner then button(
                    "Delete Group",
                    onClick.mapTo(()) --> { _ =>
                      BackendClient.deleteGroup(auth.sessionToken, g.id).foreach {
                        case Right(_) =>
                          if selectedGroupVar.now().exists(_.id == g.id) then selectedGroupVar.set(None)
                          loadGroups()
                          errorVar.set(s"Deleted group #${g.id}")
                        case Left(err) =>
                          errorVar.set(s"Delete failed: $err")
                      }
                    }
                  ) else span(),
                  button("Close", onClick.mapTo(()) --> { _ => selectedGroupVar.set(None) })
                )
              ),
              p(cls := "status-line", child.text <-- memberErrorVar.signal),
              div(
                cls := "detail-card",
                h4("Members"),
                child <-- membersVar.signal.map {
                  case Nil => p(cls := "hint", "No members yet.")
                  case members =>
                    ul(
                      members.map { uid =>
                        li(
                          span(s"User $uid"),
                          if isOwner then button(
                            "Remove",
                            onClick.mapTo(()) --> { _ =>
                              BackendClient.removeGroupMember(auth.sessionToken, g.id, uid).foreach {
                                case Right(_) =>
                                  loadMembers(g.id)
                                  errorVar.set(s"Removed user $uid from group #${g.id}")
                                case Left(err) =>
                                  memberErrorVar.set(s"Remove failed: $err")
                              }
                            }
                          ) else span()
                        )
                      }
                    )
                },
                if isOwner then div(
                  cls := "inline-form",
                  input(
                    typ := "text",
                    placeholder := "User ID to add",
                    controlled(value <-- newMemberIdVar.signal, onInput.mapToValue --> newMemberIdVar.writer)
                  ),
                  button(
                    "Add Member",
                    onClick.mapTo(()) --> { _ =>
                      scala.util.Try(newMemberIdVar.now().trim.toLong).toOption match {
                        case Some(uid) =>
                          BackendClient.addGroupMember(auth.sessionToken, g.id, uid).foreach {
                            case Right(_) =>
                              newMemberIdVar.set("")
                              loadMembers(g.id)
                              errorVar.set(s"Added user $uid")
                            case Left(err) =>
                              memberErrorVar.set(s"Add member failed: $err")
                          }
                        case None =>
                          memberErrorVar.set("Enter a valid numeric user ID")
                      }
                    }
                  )
                ) else p(cls := "hint", "Only the owner can edit members.")
              )
            )
        }
      ),
      p(cls := "status-line management-status", child.text <-- errorVar.signal)
    )
  }
}
