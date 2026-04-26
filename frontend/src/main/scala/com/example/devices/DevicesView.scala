package com.example.devices

import com.example.api.{ActiveSession, AuthResponse, BackendClient}
import com.raquo.laminar.api.L.*
import scala.scalajs.js.Date
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object DevicesView {
  def view(auth: AuthResponse, onLogout: () => Unit): HtmlElement = {
    val sessionsVar = Var(List.empty[ActiveSession])
    val passwordVar = Var("")
    val messageVar = Var("")
    val cursorVar = Var(Option.empty[String])
    val nextCursorVar = Var(Option.empty[String])

    val defaultPageSize = 10

    def refreshSessions(resetCursor: Boolean): Unit = {
      messageVar.set("Refreshing sessions...")
      val cursor = if resetCursor then None else cursorVar.now()
      BackendClient.listSessions(auth.sessionToken, cursor, defaultPageSize).foreach {
        case Right(page) =>
          sessionsVar.set(page.items)
          nextCursorVar.set(page.nextCursor)
          cursorVar.set(cursor)
          val continuation = page.nextCursor.fold("no more pages")(value => s"nextCursor=$value")
          messageVar.set(s"Loaded ${page.items.size} sessions, $continuation")
        case Left(error) =>
          messageVar.set(s"Failed to load sessions: $error")
      }
    }

    def loadNextSessionsPage(): Unit = {
      nextCursorVar.now() match {
        case Some(cursor) =>
          cursorVar.set(Some(cursor))
          refreshSessions(resetCursor = false)
        case None =>
          messageVar.set("No more sessions.")
      }
    }

    def logoutOthers(): Unit = {
      val password = passwordVar.now()
      if password.isEmpty then {
        messageVar.set("Password is required for re-authentication.")
      } else {
        messageVar.set("Logging out other devices...")
        BackendClient.logoutOthers(auth.sessionToken, password).foreach {
          case Right(_) =>
            passwordVar.set("")
            messageVar.set("Other devices were logged out.")
            cursorVar.set(None)
            refreshSessions(resetCursor = true)
          case Left(error) =>
            messageVar.set(s"Failed to log out other devices: $error")
        }
      }
    }

    div(
      cls := "devices-layout",
      div(
        cls := "panel-card",
        div(
          cls := "panel-head",
          h3("Device Sessions"),
          div(
            cls := "head-actions",
            button("Refresh", onClick.mapTo(()) --> (_ => {
              cursorVar.set(None)
              refreshSessions(resetCursor = true)
            })),
            button("Next Page", onClick.mapTo(()) --> (_ => loadNextSessionsPage())),
            button("Sign Out", onClick.mapTo(()) --> (_ => onLogout()))
          )
        ),
        div(
          cls := "detail-grid",
          div(
            cls := "detail-card",
            h4("Current User"),
            p(s"User id: ${auth.userId}"),
            p(s"Session token: ${auth.sessionToken.take(10)}...")
          ),
          div(
            cls := "detail-card",
            h4("Secure Session Cleanup"),
            p("Re-enter your password to log out all other devices."),
            div(
              cls := "inline-form",
              input(
                typ("password"),
                placeholder := "Re-enter password",
                controlled(
                  value <-- passwordVar.signal,
                  onInput.mapToValue --> passwordVar
                )
              ),
              button("Logout Other Devices", onClick.mapTo(()) --> (_ => logoutOthers()))
            )
          )
        ),
        div(
          cls := "session-grid",
          children <-- sessionsVar.signal.map { sessions =>
            if sessions.isEmpty then {
              List(p(cls := "hint", "No active sessions."))
            } else {
              sessions.map { session =>
                val when = new Date(session.createdAtEpochMillis.toDouble).toLocaleString()
                div(
                  cls := "session-card",
                  h4(session.deviceId),
                  p(s"Started: $when"),
                  if session.sessionToken == auth.sessionToken then {
                    span(cls := "entity-badge", "this device")
                  } else {
                    span(cls := "entity-badge", "remote")
                  }
                )
              }
            }
          }
        ),
        p(cls := "status-line", child.text <-- messageVar.signal)
      ),
      onMountCallback(_ => refreshSessions(resetCursor = true))
    )
  }
}