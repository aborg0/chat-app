package com.example.api

import zio.json.*

final case class RegisterRequest(username: String, password: String)
object RegisterRequest {
  given JsonEncoder[RegisterRequest] = DeriveJsonEncoder.gen[RegisterRequest]
  given JsonDecoder[RegisterRequest] = DeriveJsonDecoder.gen[RegisterRequest]
}

final case class RegisterResponse(userId: Long)
object RegisterResponse {
  given JsonEncoder[RegisterResponse] = DeriveJsonEncoder.gen[RegisterResponse]
  given JsonDecoder[RegisterResponse] = DeriveJsonDecoder.gen[RegisterResponse]
}

final case class LoginRequest(username: String, password: String, deviceId: String)
object LoginRequest {
  given JsonEncoder[LoginRequest] = DeriveJsonEncoder.gen[LoginRequest]
  given JsonDecoder[LoginRequest] = DeriveJsonDecoder.gen[LoginRequest]
}

final case class SocialLoginRequest(
  provider: String,
  providerUserId: String,
  displayName: Option[String],
  deviceId: String
)
object SocialLoginRequest {
  given JsonEncoder[SocialLoginRequest] = DeriveJsonEncoder.gen[SocialLoginRequest]
  given JsonDecoder[SocialLoginRequest] = DeriveJsonDecoder.gen[SocialLoginRequest]
}

final case class AuthResponse(userId: Long, sessionToken: String)
object AuthResponse {
  given JsonEncoder[AuthResponse] = DeriveJsonEncoder.gen[AuthResponse]
  given JsonDecoder[AuthResponse] = DeriveJsonDecoder.gen[AuthResponse]
}

final case class OAuthAuthorizeResponse(redirectUri: String, state: String)
object OAuthAuthorizeResponse {
  given JsonEncoder[OAuthAuthorizeResponse] = DeriveJsonEncoder.gen[OAuthAuthorizeResponse]
  given JsonDecoder[OAuthAuthorizeResponse] = DeriveJsonDecoder.gen[OAuthAuthorizeResponse]
}

final case class OAuthCallbackRequest(provider: String, code: String, state: String, codeVerifier: String, deviceId: String)
object OAuthCallbackRequest {
  given JsonEncoder[OAuthCallbackRequest] = DeriveJsonEncoder.gen[OAuthCallbackRequest]
  given JsonDecoder[OAuthCallbackRequest] = DeriveJsonDecoder.gen[OAuthCallbackRequest]
}

final case class ActiveSession(sessionToken: String, deviceId: String, createdAtEpochMillis: Long)
object ActiveSession {
  given JsonEncoder[ActiveSession] = DeriveJsonEncoder.gen[ActiveSession]
  given JsonDecoder[ActiveSession] = DeriveJsonDecoder.gen[ActiveSession]
}

final case class ActiveSessionsPage(items: List[ActiveSession], nextCursor: Option[String])
object ActiveSessionsPage {
  given JsonEncoder[ActiveSessionsPage] = DeriveJsonEncoder.gen[ActiveSessionsPage]
  given JsonDecoder[ActiveSessionsPage] = DeriveJsonDecoder.gen[ActiveSessionsPage]
}

final case class LogoutOthersRequest(password: Option[String])
object LogoutOthersRequest {
  given JsonEncoder[LogoutOthersRequest] = DeriveJsonEncoder.gen[LogoutOthersRequest]
  given JsonDecoder[LogoutOthersRequest] = DeriveJsonDecoder.gen[LogoutOthersRequest]
}

final case class CreateMessageRequest(content: String, clientEditedAtEpochMillis: Option[Long] = None)
object CreateMessageRequest {
  given JsonEncoder[CreateMessageRequest] = DeriveJsonEncoder.gen[CreateMessageRequest]
  given JsonDecoder[CreateMessageRequest] = DeriveJsonDecoder.gen[CreateMessageRequest]
}

final case class EditMessageRequest(
  content: String,
  expectedVersion: Option[Int] = None,
  clientEditedAtEpochMillis: Option[Long] = None
)
object EditMessageRequest {
  given JsonEncoder[EditMessageRequest] = DeriveJsonEncoder.gen[EditMessageRequest]
  given JsonDecoder[EditMessageRequest] = DeriveJsonDecoder.gen[EditMessageRequest]
}

final case class MessageResponse(
  id: Long,
  authorUserId: Long,
  content: String,
  deleted: Boolean,
  version: Int,
  createdAtEpochMillis: Long,
  updatedAtEpochMillis: Long,
  clientEditedAtEpochMillis: Option[Long],
  deepLink: String
)
object MessageResponse {
  given JsonEncoder[MessageResponse] = DeriveJsonEncoder.gen[MessageResponse]
  given JsonDecoder[MessageResponse] = DeriveJsonDecoder.gen[MessageResponse]
}

final case class MessageSearchPage(items: List[MessageResponse], nextCursor: Option[String])
object MessageSearchPage {
  given JsonEncoder[MessageSearchPage] = DeriveJsonEncoder.gen[MessageSearchPage]
  given JsonDecoder[MessageSearchPage] = DeriveJsonDecoder.gen[MessageSearchPage]
}

final case class MessageHistoryEntry(
  version: Int,
  previousContent: String,
  newContent: String,
  editedByUserId: Long,
  editedAtEpochMillis: Long
)
object MessageHistoryEntry {
  given JsonEncoder[MessageHistoryEntry] = DeriveJsonEncoder.gen[MessageHistoryEntry]
  given JsonDecoder[MessageHistoryEntry] = DeriveJsonDecoder.gen[MessageHistoryEntry]
}

final case class AuditEntryResponse(
  id: Long,
  actorUserId: Long,
  action: String,
  targetUserId: Option[Long],
  messageId: Option[Long],
  details: Option[String],
  createdAtEpochMillis: Long
)
object AuditEntryResponse {
  given JsonEncoder[AuditEntryResponse] = DeriveJsonEncoder.gen[AuditEntryResponse]
  given JsonDecoder[AuditEntryResponse] = DeriveJsonDecoder.gen[AuditEntryResponse]
}

final case class AuditEntriesPage(items: List[AuditEntryResponse], nextCursor: Option[String])
object AuditEntriesPage {
  given JsonEncoder[AuditEntriesPage] = DeriveJsonEncoder.gen[AuditEntriesPage]
  given JsonDecoder[AuditEntriesPage] = DeriveJsonDecoder.gen[AuditEntriesPage]
}

// ---- Chapters ----

final case class CreateChapterRequest(title: String, parentChapterId: Option[Long])
object CreateChapterRequest {
  given JsonEncoder[CreateChapterRequest] = DeriveJsonEncoder.gen[CreateChapterRequest]
  given JsonDecoder[CreateChapterRequest] = DeriveJsonDecoder.gen[CreateChapterRequest]
}

final case class UpdateChapterVisibilityRequest(visibility: String) // private | individuals | authenticated | group | public
object UpdateChapterVisibilityRequest {
  given JsonEncoder[UpdateChapterVisibilityRequest] = DeriveJsonEncoder.gen[UpdateChapterVisibilityRequest]
  given JsonDecoder[UpdateChapterVisibilityRequest] = DeriveJsonDecoder.gen[UpdateChapterVisibilityRequest]
}

final case class AddChapterMemberRequest(userId: Long, role: String) // viewer | editor
object AddChapterMemberRequest {
  given JsonEncoder[AddChapterMemberRequest] = DeriveJsonEncoder.gen[AddChapterMemberRequest]
  given JsonDecoder[AddChapterMemberRequest] = DeriveJsonDecoder.gen[AddChapterMemberRequest]
}

final case class AddMessageToChapterRequest(messageId: Long)
object AddMessageToChapterRequest {
  given JsonEncoder[AddMessageToChapterRequest] = DeriveJsonEncoder.gen[AddMessageToChapterRequest]
  given JsonDecoder[AddMessageToChapterRequest] = DeriveJsonDecoder.gen[AddMessageToChapterRequest]
}

final case class ChapterResponse(
  id: Long,
  ownerUserId: Long,
  title: String,
  parentChapterId: Option[Long],
  visibility: String,
  createdAtEpochMillis: Long
)
object ChapterResponse {
  given JsonEncoder[ChapterResponse] = DeriveJsonEncoder.gen[ChapterResponse]
  given JsonDecoder[ChapterResponse] = DeriveJsonDecoder.gen[ChapterResponse]
}

final case class ChapterMemberResponse(userId: Long, role: String)
object ChapterMemberResponse {
  given JsonEncoder[ChapterMemberResponse] = DeriveJsonEncoder.gen[ChapterMemberResponse]
  given JsonDecoder[ChapterMemberResponse] = DeriveJsonDecoder.gen[ChapterMemberResponse]
}

final case class ChapterDetailResponse(
  chapter: ChapterResponse,
  members: List[ChapterMemberResponse],
  messageIds: List[Long]
)
object ChapterDetailResponse {
  given JsonEncoder[ChapterDetailResponse] = DeriveJsonEncoder.gen[ChapterDetailResponse]
  given JsonDecoder[ChapterDetailResponse] = DeriveJsonDecoder.gen[ChapterDetailResponse]
}

final case class UpdateChapterPreferenceRequest(isImportant: Boolean, muteLevel: String) // none | soft | hard
object UpdateChapterPreferenceRequest {
  given JsonEncoder[UpdateChapterPreferenceRequest] = DeriveJsonEncoder.gen[UpdateChapterPreferenceRequest]
  given JsonDecoder[UpdateChapterPreferenceRequest] = DeriveJsonDecoder.gen[UpdateChapterPreferenceRequest]
}

final case class ChapterPreferenceResponse(
  chapterId: Long,
  isImportant: Boolean,
  muteLevel: String,
  updatedAtEpochMillis: Long
)
object ChapterPreferenceResponse {
  given JsonEncoder[ChapterPreferenceResponse] = DeriveJsonEncoder.gen[ChapterPreferenceResponse]
  given JsonDecoder[ChapterPreferenceResponse] = DeriveJsonDecoder.gen[ChapterPreferenceResponse]
}

final case class ChapterUnreadCountResponse(chapterId: Long, unreadCount: Int, muteLevel: String)
object ChapterUnreadCountResponse {
  given JsonEncoder[ChapterUnreadCountResponse] = DeriveJsonEncoder.gen[ChapterUnreadCountResponse]
  given JsonDecoder[ChapterUnreadCountResponse] = DeriveJsonDecoder.gen[ChapterUnreadCountResponse]
}

final case class ShareLinkResponse(token: String)
object ShareLinkResponse {
  given JsonEncoder[ShareLinkResponse] = DeriveJsonEncoder.gen[ShareLinkResponse]
  given JsonDecoder[ShareLinkResponse] = DeriveJsonDecoder.gen[ShareLinkResponse]
}

// target: "chapter" or "message"
final case class ShareLinkTargetResponse(targetType: String, chapterId: Option[Long], messageId: Option[Long])
object ShareLinkTargetResponse {
  given JsonEncoder[ShareLinkTargetResponse] = DeriveJsonEncoder.gen[ShareLinkTargetResponse]
  given JsonDecoder[ShareLinkTargetResponse] = DeriveJsonDecoder.gen[ShareLinkTargetResponse]
}

// ---- Groups ----

final case class CreateGroupRequest(name: String)
object CreateGroupRequest {
  given JsonEncoder[CreateGroupRequest] = DeriveJsonEncoder.gen[CreateGroupRequest]
  given JsonDecoder[CreateGroupRequest] = DeriveJsonDecoder.gen[CreateGroupRequest]
}

final case class GroupResponse(id: Long, ownerUserId: Long, name: String, createdAtEpochMillis: Long)
object GroupResponse {
  given JsonEncoder[GroupResponse] = DeriveJsonEncoder.gen[GroupResponse]
  given JsonDecoder[GroupResponse] = DeriveJsonDecoder.gen[GroupResponse]
}

final case class GroupMemberRequest(userId: Long)
object GroupMemberRequest {
  given JsonEncoder[GroupMemberRequest] = DeriveJsonEncoder.gen[GroupMemberRequest]
  given JsonDecoder[GroupMemberRequest] = DeriveJsonDecoder.gen[GroupMemberRequest]
}

final case class GroupMembersResponse(memberUserIds: List[Long])
object GroupMembersResponse {
  given JsonEncoder[GroupMembersResponse] = DeriveJsonEncoder.gen[GroupMembersResponse]
  given JsonDecoder[GroupMembersResponse] = DeriveJsonDecoder.gen[GroupMembersResponse]
}

final case class ChapterGroupAccessRequest(groupId: Long)
object ChapterGroupAccessRequest {
  given JsonEncoder[ChapterGroupAccessRequest] = DeriveJsonEncoder.gen[ChapterGroupAccessRequest]
  given JsonDecoder[ChapterGroupAccessRequest] = DeriveJsonDecoder.gen[ChapterGroupAccessRequest]
}

final case class ChapterGroupAccessResponse(groupIds: List[Long])
object ChapterGroupAccessResponse {
  given JsonEncoder[ChapterGroupAccessResponse] = DeriveJsonEncoder.gen[ChapterGroupAccessResponse]
  given JsonDecoder[ChapterGroupAccessResponse] = DeriveJsonDecoder.gen[ChapterGroupAccessResponse]
}