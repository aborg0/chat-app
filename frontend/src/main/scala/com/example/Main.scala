package com.example

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import com.example.api.AuthResponse
import com.example.auth.LoginView
import com.example.chapters.ChaptersView
import com.example.devices.DevicesView
import com.example.groups.GroupsView
import com.example.messaging.ChatView

object Main {
  sealed trait AppTab
  object AppTab {
    case object Chapters extends AppTab
    case object Groups extends AppTab
    case object Messaging extends AppTab
    case object Devices extends AppTab
  }

  def main(args: Array[String]): Unit = {
    val root = dom.document.getElementById("app")
    if root != null then {
      val authVar = Var(Option.empty[AuthResponse])
      val tabVar = Var[AppTab](AppTab.Messaging)

      render(
        root,
        div(
          cls := "app-root",
          child <-- authVar.signal.map {
            case Some(auth) =>
              div(
                cls := "workspace",
                div(
                  cls := "workspace-sidebar",
                  div(
                    cls := "workspace-brand",
                    h2("Chatty"),
                    span("team console")
                  ),
                  button(
                    cls := "workspace-nav-btn",
                    cls("active") <-- tabVar.signal.map(_ == AppTab.Messaging),
                    "Messaging",
                    onClick.mapTo(()) --> (_ => tabVar.set(AppTab.Messaging))
                  ),
                  button(
                    cls := "workspace-nav-btn",
                    cls("active") <-- tabVar.signal.map(_ == AppTab.Chapters),
                    "Chapters",
                    onClick.mapTo(()) --> (_ => tabVar.set(AppTab.Chapters))
                  ),
                  button(
                    cls := "workspace-nav-btn",
                    cls("active") <-- tabVar.signal.map(_ == AppTab.Groups),
                    "Groups",
                    onClick.mapTo(()) --> (_ => tabVar.set(AppTab.Groups))
                  ),
                  button(
                    cls := "workspace-nav-btn",
                    cls("active") <-- tabVar.signal.map(_ == AppTab.Devices),
                    "Devices",
                    onClick.mapTo(()) --> (_ => tabVar.set(AppTab.Devices))
                  ),
                  div(
                    cls := "workspace-sidebar-footer",
                    span(s"user ${auth.userId}"),
                    button(
                      cls := "workspace-logout",
                      "Log Out",
                      onClick.mapTo(()) --> (_ => {
                        tabVar.set(AppTab.Messaging)
                        authVar.set(None)
                      })
                    )
                  )
                ),
                div(
                  cls := "workspace-main",
                  div(
                    cls := "workspace-topbar",
                    h1(child.text <-- tabVar.signal.map {
                      case AppTab.Messaging => "Messaging Hub"
                      case AppTab.Chapters  => "Chapter Management"
                      case AppTab.Groups    => "Group Directory"
                      case AppTab.Devices   => "Session Devices"
                    }),
                    a(href := "/swagger", target := "_blank", rel := "noopener noreferrer", "API Docs")
                  ),
                  div(
                    cls := "workspace-content",
                    child <-- tabVar.signal.map {
                      case AppTab.Messaging =>
                        ChatView.view(auth)
                      case AppTab.Chapters =>
                        ChaptersView.view(auth)
                      case AppTab.Groups =>
                        GroupsView.view(auth)
                      case AppTab.Devices =>
                        DevicesView.view(
                          auth = auth,
                          onLogout = () => {
                            tabVar.set(AppTab.Messaging)
                            authVar.set(None)
                          }
                        )
                    }
                  )
                )
              )
            case None =>
              LoginView.view(auth => {
                tabVar.set(AppTab.Messaging)
                authVar.set(Some(auth))
              })
          }
        )
      )
    }
  }
}