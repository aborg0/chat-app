package com.example.api

import zio.json.*

/**
 * Compile-time verification tests for backend API types.
 * These tests verify that:
 * 1. All request/response types can be instantiated
 * 2. JSON encoders/decoders are available
 * 3. Type signatures are correct
 */
object BackendClientCompileTimeTests {
  // Compile-time verification that all types are available and can be used

  def verifyRegisterRequest(): Unit = {
    val req = RegisterRequest("user", "pass")
    val json = req.toJson
    val parsed = json.fromJson[RegisterRequest]
  }

  def verifyLoginRequest(): Unit = {
    val req = LoginRequest("user", "pass", "device")
    val json = req.toJson
    val parsed = json.fromJson[LoginRequest]
  }

  def verifyAuthResponse(): Unit = {
    val resp = AuthResponse(123, "token")
    val json = resp.toJson
    val parsed = json.fromJson[AuthResponse]
  }

  def verifyChapterResponse(): Unit = {
    val ch = ChapterResponse(1, 42, "Title", None, "private", 1234567890L)
    val json = ch.toJson
    val parsed = json.fromJson[ChapterResponse]
  }

  def verifyChapterMemberResponse(): Unit = {
    val m = ChapterMemberResponse(123, "viewer")
    val json = m.toJson
    val parsed = json.fromJson[ChapterMemberResponse]
  }

  def verifyChapterDetailResponse(): Unit = {
    val detail = ChapterDetailResponse(
      ChapterResponse(1, 42, "Title", None, "private", 1234567890L),
      List(ChapterMemberResponse(123, "viewer")),
      List(100, 101)
    )
    val json = detail.toJson
    val parsed = json.fromJson[ChapterDetailResponse]
  }

  def verifyShareLinkResponse(): Unit = {
    val link = ShareLinkResponse("token123")
    val json = link.toJson
    val parsed = json.fromJson[ShareLinkResponse]
  }

  def verifyCreateChapterRequest(): Unit = {
    val req = CreateChapterRequest("Title", None)
    val json = req.toJson
    val parsed = json.fromJson[CreateChapterRequest]
  }

  def verifyUpdateChapterVisibilityRequest(): Unit = {
    val req = UpdateChapterVisibilityRequest("public")
    val json = req.toJson
    val parsed = json.fromJson[UpdateChapterVisibilityRequest]
  }

  def verifyShareLinkTargetResponse(): Unit = {
    val target = ShareLinkTargetResponse("chapter", Some(1), None)
    val json = target.toJson
    val parsed = json.fromJson[ShareLinkTargetResponse]
  }
}


