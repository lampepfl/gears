package gears.async

import language.experimental.captureChecking
import caps.*

object capabilities:
  /** Suspension capability classifier. All `SuspendSupport` implementations must only capture capabilities under this
    * classifier.
    */
  // TODO: move this to under ThreadLocal
  trait Suspension extends Classifier, SharedCapability

  /** Scoping capability classifier. Only used by CompletetionGroups. */
  trait Scoping extends Classifier, SharedCapability
