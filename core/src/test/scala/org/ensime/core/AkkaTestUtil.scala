package org.ensime.core

import akka.actor._
import akka.event.slf4j.SLF4JLogging
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import org.scalatest.Assertions
import java.util.UUID
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag

/**
 * Convenience for the boilerplate of setting up a test using an Akka
 * System.
 *
 * Ideally we'd reuse the TestkitFixture, but sbt makes it *really*
 * hard to share test utilities between unit tests and integration
 * tests.
 *
 * http://stackoverflow.com/questions/20624574
 */
abstract class AkkaFlatSpec
  extends HasSystem with TestKitBase
  with DefaultTimeout with ImplicitSender
  with FlatSpecLike with SetupAndTearDownSystem
  with Matchers with SLF4JLogging

// for when `system` must be defined ahead of a mixin
trait HasSystem {
  implicit val system = ActorSystem()
}

trait SetupAndTearDownSystem extends BeforeAndAfterAll {
  this: Suite with TestKitBase =>
  // can't mix in SetupAndTearDownSystem because fixture._ is different API
  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }
  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}
