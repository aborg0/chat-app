package com.example.messaging

import com.example.api.TypingIndicatorResponse
import org.scalajs.dom
import zio.json.*

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object TypingIndicatorClient {

  private final case class TypingEventPayload(`type`: String)
  private object TypingEventPayload {
    given JsonEncoder[TypingEventPayload] = DeriveJsonEncoder.gen[TypingEventPayload]
    given JsonDecoder[TypingEventPayload] = DeriveJsonDecoder.gen[TypingEventPayload]
  }

  private val sockets = mutable.Map.empty[Long, dom.WebSocket]
  private val listeners = mutable.Map.empty[Long, Vector[TypingIndicatorResponse => Unit]]

  def connect(chapterId: Long, sessionToken: String): Future[Unit] = Future {
    disconnect(chapterId)
    val socket = new dom.WebSocket(webSocketUrl(chapterId))
    socket.onopen = _ => {
      socket.send(TypingEventPayload("started").toJson)
      ()
    }
    socket.onmessage = event => {
      val maybeText =
        if js.typeOf(event.data.asInstanceOf[js.Any]) == "string" then Some(event.data.asInstanceOf[String])
        else None
      maybeText
        .flatMap(_.fromJson[TypingIndicatorResponse].toOption)
        .foreach { payload =>
          listeners.getOrElse(chapterId, Vector.empty).foreach(callback => callback(payload))
        }
    }
    socket.onerror = _ => ()
    socket.onclose = _ => {
      sockets.remove(chapterId)
      ()
    }
    sockets.update(chapterId, socket)
  }

  def startTyping(chapterId: Long): Unit =
    sockets.get(chapterId).filter(_.readyState == dom.WebSocket.OPEN).foreach(_.send(TypingEventPayload("started").toJson))

  def stopTyping(chapterId: Long): Unit =
    sockets.get(chapterId).filter(_.readyState == dom.WebSocket.OPEN).foreach(_.send(TypingEventPayload("stopped").toJson))

  def onTypingUpdate(chapterId: Long)(callback: TypingIndicatorResponse => Unit): Unit =
    listeners.update(chapterId, listeners.getOrElse(chapterId, Vector.empty) :+ callback)

  def disconnect(chapterId: Long): Unit = {
    sockets.remove(chapterId).foreach(_.close())
    listeners.remove(chapterId)
  }

  private def webSocketUrl(chapterId: Long): String = {
    val location = dom.window.location
    val protocol = if location.protocol == "https:" then "wss" else "ws"
    s"$protocol://${location.hostname}:8080/typing/subscribe?chapterId=$chapterId"
  }
}