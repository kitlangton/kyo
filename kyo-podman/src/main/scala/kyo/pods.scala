package kyo

import kyo._
import kyo.ios._
import kyo.requests._
import kyo.concurrent.fibers._

object pods {

  abstract class Pod {
    def eval[T](thunk: => T): T > Pods
  }

  object Pods {
    type Effects = Requests
    def init(image: String): Pod > Pods
  }
}