package com.example.messaging

import zio.Scope
import zio.test.*

object MessagingModuleSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MessagingModuleSpec")(
      test("optimistic concurrency matches when expected version equals current") {
        assertTrue(MessagingModule.OptimisticConcurrency.matches(Some(3), 3))
      },
      test("optimistic concurrency rejects mismatched versions") {
        assertTrue(!MessagingModule.OptimisticConcurrency.matches(Some(2), 3))
      },
      test("optimistic concurrency allows no expected version") {
        assertTrue(MessagingModule.OptimisticConcurrency.matches(None, 7))
      },
      test("next version increments by one") {
        assertTrue(MessagingModule.OptimisticConcurrency.nextVersion(9) == 10)
      }
    )
}
