package com.example.api

import org.scalajs.dom
import org.scalajs.dom.{Headers, RequestInit}
import zio.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.Uint8Array

object BackendClient {
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
  private val TraceparentHeader = "traceparent"

  private def baseUrl: String = {
    val stored = Option(dom.window.localStorage.getItem("backendBaseUrl")).filter(_.nonEmpty)
    stored.getOrElse(s"${dom.window.location.protocol}//${dom.window.location.hostname}:8080")
  }

  def register(username: String, password: String): Future[Either[String, RegisterResponse]] = {
    postJson[RegisterRequest, RegisterResponse](
      "/auth/register",
      RegisterRequest(username, password)
    )
  }

  def login(username: String, password: String, deviceId: String): Future[Either[String, AuthResponse]] = {
    postJson[LoginRequest, AuthResponse](
      "/auth/login",
      LoginRequest(username, password, deviceId)
    )
  }

  def socialLogin(
    provider: String,
    providerUserId: String,
    displayName: Option[String],
    deviceId: String
  ): Future[Either[String, AuthResponse]] = {
    postJson[SocialLoginRequest, AuthResponse](
      "/auth/social-login",
      SocialLoginRequest(provider, providerUserId, displayName, deviceId)
    )
  }

  def listSessions(sessionToken: String, cursor: Option[String], pageSize: Int): Future[Either[String, ActiveSessionsPage]] = {
    val cursorPart = cursor.map(value => s"&cursor=${URIUtils.encodeURIComponent(value)}").getOrElse("")
    getJson[ActiveSessionsPage](
      s"/sessions?pageSize=$pageSize$cursorPart",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def logoutOthers(sessionToken: String, password: String): Future[Either[String, Unit]] = {
    postNoResponse(
      "/sessions/logout-others",
      LogoutOthersRequest(Some(password)),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def createMessage(
    sessionToken: String,
    content: String,
    clientEditedAtEpochMillis: Option[Long] = None
  ): Future[Either[String, MessageResponse]] = {
    postJson[CreateMessageRequest, MessageResponse](
      "/messages",
      CreateMessageRequest(content, clientEditedAtEpochMillis),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def searchMessages(
    sessionToken: String,
    query: String,
    targetUserId: Option[Long],
    cursor: Option[String],
    pageSize: Int
  ): Future[Either[String, MessageSearchPage]] = {
    val encodedQuery = URIUtils.encodeURIComponent(query)
    val targetPart = targetUserId.map(id => s"&targetUserId=$id").getOrElse("")
    val cursorPart = cursor.map(value => s"&cursor=${URIUtils.encodeURIComponent(value)}").getOrElse("")
    getJson[MessageSearchPage](
      s"/messages/search?q=$encodedQuery$targetPart$cursorPart&pageSize=$pageSize",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def getMessageById(sessionToken: String, messageId: Long): Future[Either[String, MessageResponse]] = {
    getJson[MessageResponse](
      s"/messages/by-id?messageId=$messageId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def editMessage(
    sessionToken: String,
    messageId: Long,
    content: String,
    expectedVersion: Option[Int] = None,
    clientEditedAtEpochMillis: Option[Long] = None
  ): Future[Either[String, MessageResponse]] = {
    val path = s"/messages/by-id?messageId=$messageId"
    putJson[EditMessageRequest, MessageResponse](
      path,
      EditMessageRequest(content, expectedVersion, clientEditedAtEpochMillis),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def deleteMessage(sessionToken: String, messageId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/messages/by-id?messageId=$messageId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def messageHistory(sessionToken: String, messageId: Long): Future[Either[String, List[MessageHistoryEntry]]] = {
    getJson[List[MessageHistoryEntry]](
      s"/messages/history?messageId=$messageId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listAuditEntries(
    sessionToken: String,
    targetUserId: Option[Long],
    messageId: Option[Long],
    cursor: Option[String],
    pageSize: Int
  ): Future[Either[String, AuditEntriesPage]] = {
    val queryParts = List(
      targetUserId.map(id => s"targetUserId=$id"),
      messageId.map(id => s"messageId=$id"),
      cursor.map(value => s"cursor=${URIUtils.encodeURIComponent(value)}"),
      Some(s"pageSize=$pageSize")
    ).flatten
    val querySuffix = if queryParts.nonEmpty then queryParts.mkString("?", "&", "") else ""
    getJson[AuditEntriesPage](
      s"/admin/audit$querySuffix",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  // ---- Chapters ----

  def createChapter(sessionToken: String, title: String, parentChapterId: Option[Long]): Future[Either[String, ChapterResponse]] = {
    postJson[CreateChapterRequest, ChapterResponse](
      "/chapters",
      CreateChapterRequest(title, parentChapterId),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listChapters(sessionToken: String): Future[Either[String, List[ChapterResponse]]] = {
    getJson[List[ChapterResponse]](
      "/chapters",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def getChapter(sessionToken: String, chapterId: Long): Future[Either[String, ChapterDetailResponse]] = {
    getJson[ChapterDetailResponse](
      s"/chapters/$chapterId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listChapterMessages(
    sessionToken: String,
    chapterId: Long,
    cursor: Option[String],
    pageSize: Int
  ): Future[Either[String, MessageSearchPage]] = {
    val cursorPart = cursor.map(value => s"&cursor=${URIUtils.encodeURIComponent(value)}").getOrElse("")
    getJson[MessageSearchPage](
      s"/chapters/$chapterId/messages?pageSize=$pageSize$cursorPart",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def deleteChapter(sessionToken: String, chapterId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/chapters/$chapterId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def updateChapterVisibility(sessionToken: String, chapterId: Long, visibility: String): Future[Either[String, Unit]] = {
    putNoResponse[UpdateChapterVisibilityRequest](
      s"/chapters/$chapterId/visibility",
      UpdateChapterVisibilityRequest(visibility),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def addChapterMember(sessionToken: String, chapterId: Long, userId: Long, role: String): Future[Either[String, Unit]] = {
    postNoResponse[AddChapterMemberRequest](
      s"/chapters/$chapterId/members",
      AddChapterMemberRequest(userId, role),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def removeChapterMember(sessionToken: String, chapterId: Long, userId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/chapters/$chapterId/members/$userId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def addMessageToChapter(sessionToken: String, chapterId: Long, messageId: Long): Future[Either[String, Unit]] = {
    postNoResponse[AddMessageToChapterRequest](
      s"/chapters/$chapterId/messages",
      AddMessageToChapterRequest(messageId),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def removeMessageFromChapter(sessionToken: String, chapterId: Long, messageId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/chapters/$chapterId/messages/$messageId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def createChapterShareLink(sessionToken: String, chapterId: Long): Future[Either[String, ShareLinkResponse]] = {
    postEmptyJson[ShareLinkResponse](
      s"/chapters/$chapterId/share-link",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listChapterPreferences(sessionToken: String): Future[Either[String, List[ChapterPreferenceResponse]]] = {
    getJson[List[ChapterPreferenceResponse]](
      "/chapters/preferences",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def getChapterPreference(sessionToken: String, chapterId: Long): Future[Either[String, ChapterPreferenceResponse]] = {
    getJson[ChapterPreferenceResponse](
      s"/chapters/$chapterId/preferences",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def updateChapterPreference(
    sessionToken: String,
    chapterId: Long,
    isImportant: Boolean,
    muteLevel: String
  ): Future[Either[String, ChapterPreferenceResponse]] = {
    putJson[UpdateChapterPreferenceRequest, ChapterPreferenceResponse](
      s"/chapters/$chapterId/preferences",
      UpdateChapterPreferenceRequest(isImportant, muteLevel),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def chapterUnreadCount(sessionToken: String, chapterId: Long): Future[Either[String, ChapterUnreadCountResponse]] = {
    getJson[ChapterUnreadCountResponse](
      s"/chapters/$chapterId/unread-count",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def markMessageRead(sessionToken: String, chapterId: Long, messageId: Long): Future[Either[String, Unit]] = {
    postEmptyNoResponse(
      s"/chapters/$chapterId/messages/$messageId/read",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def markUnreadFrom(sessionToken: String, chapterId: Long, messageId: Long): Future[Either[String, Unit]] = {
    postEmptyNoResponse(
      s"/chapters/$chapterId/messages/$messageId/unread-from",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def createMessageShareLink(sessionToken: String, messageId: Long): Future[Either[String, ShareLinkResponse]] = {
    postEmptyJson[ShareLinkResponse](
      s"/messages/share-link?messageId=$messageId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def resolveShareLink(token: String): Future[Either[String, ShareLinkTargetResponse]] = {
    getJson[ShareLinkTargetResponse](s"/share/$token")
  }

  // ---- Drafts ----

  def getAllDrafts(sessionToken: String): Future[Either[String, List[DraftResponse]]] = {
    getJson[List[DraftResponse]](
      "/user/drafts",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def getDraft(sessionToken: String, chapterId: Long): Future[Either[String, Option[DraftResponse]]] = {
    getJson[Option[DraftResponse]](
      s"/chapters/$chapterId/draft",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def saveDraft(sessionToken: String, chapterId: Long, content: String): Future[Either[String, DraftResponse]] = {
    putJson[SaveDraftRequest, DraftResponse](
      s"/chapters/$chapterId/draft",
      SaveDraftRequest(content),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def deleteDraft(sessionToken: String, chapterId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/chapters/$chapterId/draft",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  // ---- Groups ----

  def createGroup(sessionToken: String, name: String): Future[Either[String, GroupResponse]] = {
    postJson[CreateGroupRequest, GroupResponse](
      "/groups",
      CreateGroupRequest(name),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listGroups(sessionToken: String): Future[Either[String, List[GroupResponse]]] = {
    getJson[List[GroupResponse]](
      "/groups",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def deleteGroup(sessionToken: String, groupId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/groups/$groupId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def addGroupMember(sessionToken: String, groupId: Long, userId: Long): Future[Either[String, Unit]] = {
    postNoResponse[GroupMemberRequest](
      s"/groups/$groupId/members",
      GroupMemberRequest(userId),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def removeGroupMember(sessionToken: String, groupId: Long, userId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/groups/$groupId/members/$userId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listGroupMembers(sessionToken: String, groupId: Long): Future[Either[String, GroupMembersResponse]] = {
    getJson[GroupMembersResponse](
      s"/groups/$groupId/members",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def addChapterGroupAccess(sessionToken: String, chapterId: Long, groupId: Long): Future[Either[String, Unit]] = {
    postNoResponse[ChapterGroupAccessRequest](
      s"/chapters/$chapterId/group-access",
      ChapterGroupAccessRequest(groupId),
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def removeChapterGroupAccess(sessionToken: String, chapterId: Long, groupId: Long): Future[Either[String, Unit]] = {
    deleteNoResponse(
      s"/chapters/$chapterId/group-access/$groupId",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  def listChapterGroupAccess(sessionToken: String, chapterId: Long): Future[Either[String, ChapterGroupAccessResponse]] = {
    getJson[ChapterGroupAccessResponse](
      s"/chapters/$chapterId/group-access",
      headers = Map("X-Session-Token" -> sessionToken)
    )
  }

  private def postJson[A: JsonEncoder, B: JsonDecoder](path: String, payload: A, headers: Map[String, String] = Map.empty): Future[Either[String, B]] = {
    request(path, "POST", Some(payload.toJson), headers).map {
      case Left(error) => Left(error)
      case Right(text) => text.fromJson[B].left.map(err => s"Invalid JSON response: $err")
    }
  }

  private def postEmptyJson[B: JsonDecoder](path: String, headers: Map[String, String] = Map.empty): Future[Either[String, B]] = {
    request(path, "POST", Some("{}"), headers).map {
      case Left(error) => Left(error)
      case Right(text) => text.fromJson[B].left.map(err => s"Invalid JSON response: $err")
    }
  }

  private def postEmptyNoResponse(path: String, headers: Map[String, String] = Map.empty): Future[Either[String, Unit]] = {
    request(path, "POST", Some("{}"), headers).map {
      case Left(error) => Left(error)
      case Right(_) => Right(())
    }
  }

  private def postNoResponse[A: JsonEncoder](path: String, payload: A, headers: Map[String, String] = Map.empty): Future[Either[String, Unit]] = {
    request(path, "POST", Some(payload.toJson), headers).map {
      case Left(error) => Left(error)
      case Right(_) => Right(())
    }
  }

  private def putJson[A: JsonEncoder, B: JsonDecoder](path: String, payload: A, headers: Map[String, String] = Map.empty): Future[Either[String, B]] = {
    request(path, "PUT", Some(payload.toJson), headers).map {
      case Left(error) => Left(error)
      case Right(text) => text.fromJson[B].left.map(err => s"Invalid JSON response: $err")
    }
  }

  private def putNoResponse[A: JsonEncoder](path: String, payload: A, headers: Map[String, String] = Map.empty): Future[Either[String, Unit]] = {
    request(path, "PUT", Some(payload.toJson), headers).map {
      case Left(error) => Left(error)
      case Right(_)    => Right(())
    }
  }

  private def deleteNoResponse(path: String, headers: Map[String, String] = Map.empty): Future[Either[String, Unit]] = {
    request(path, "DELETE", None, headers).map {
      case Left(error) => Left(error)
      case Right(_) => Right(())
    }
  }

  private def getJson[A: JsonDecoder](path: String, headers: Map[String, String] = Map.empty): Future[Either[String, A]] = {
    request(path, "GET", None, headers).map {
      case Left(error) => Left(error)
      case Right(text) => text.fromJson[A].left.map(err => s"Invalid JSON response: $err")
    }
  }

  private def request(
    path: String,
    methodName: String,
    requestBody: Option[String],
    extraHeaders: Map[String, String] = Map.empty
  ): Future[Either[String, String]] = {
    val requestHeaders = ensureTraceparent(extraHeaders)
    val headers = new Headers()
    headers.set("Content-Type", "application/json")
    requestHeaders.foreach { case (key, value) =>
      headers.set(key, value)
    }

    val init = js.Dynamic
      .literal(
        method = methodName,
        headers = headers,
        mode = dom.RequestMode.cors,
        credentials = dom.RequestCredentials.omit,
        body = requestBody.fold[js.UndefOr[String]](js.undefined)(identity)
      )
      .asInstanceOf[RequestInit]

    val url = s"$baseUrl$path"

    dom.fetch(url, init).toFuture.flatMap { response =>
      response.text().toFuture.map { text =>
        if response.ok then {
          Right(text)
        } else {
          val message = if text.nonEmpty then text else response.statusText
          Left(s"${response.status}: $message")
        }
      }
    }.recover {
      case error: Throwable => Left(s"Network error: ${error.getMessage}")
    }
  }

  private def ensureTraceparent(headers: Map[String, String]): Map[String, String] = {
    if headers.exists { case (key, value) => key.equalsIgnoreCase(TraceparentHeader) && value.trim.nonEmpty } then headers
    else headers + (TraceparentHeader -> generateTraceparent())
  }

  private def generateTraceparent(): String = {
    s"00-${randomHex(16)}-${randomHex(8)}-01"
  }

  private def randomHex(byteLength: Int): String = {
    val bytes = new Uint8Array(byteLength)
    dom.window.asInstanceOf[js.Dynamic].crypto.getRandomValues(bytes.asInstanceOf[js.Any])
    bytes.toArray.map(byte => f"$byte%02x").mkString
  }
}
