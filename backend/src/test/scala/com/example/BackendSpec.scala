package com.example

import com.example.auth.AuthModule.Passwords
import zio.Scope
import zio.test.*

object BackendSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("BackendSpec")(
      test("password hashing verifies") {
        for {
          hash <- Passwords.hash("Secret123!")
          ok <- Passwords.verify("Secret123!", hash)
        } yield {
          assertTrue(ok)
        }
      }
    )
  }
}