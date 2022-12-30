package kyo.bench

import kyo.core._
import kyo.ios._
import kyo.fibers._
import kyo.futures._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

object KyoRuntime {

  def runIO[T](v: T > IOs): T =
    IOs.run(v)

  def runFiber[T](v: T > IOs): T =
    IOs.run(Fibers.fork(v)(_.block))

  def runFuture[T](v: T > IOs): T =
    Futures.block(
        Futures.fork(IOs.run(v): T),
        Duration.Inf
    )
}