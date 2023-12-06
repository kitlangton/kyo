package kyo.concurrent

import kyo._
import kyo.core._
import kyo.core.internal._
import kyo.ios._
import kyo.joins._
import kyo.locals._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import scheduler._
import timers._

object fibers {

  import internal._

  private[concurrent] case class Failed(reason: Throwable)

  type Fiber[+T] // = T | Failed[T] | IOPromise[T]

  type Promise[+T] <: Fiber[T] // = IOPromise[T]

  implicit class PromiseOps[T](private val p: Promise[T]) extends AnyVal {

    def complete(v: T > IOs): Boolean > IOs =
      IOs(p.asInstanceOf[IOPromise[T]].complete(v))

    private[kyo] def unsafeComplete(v: T > IOs): Boolean =
      p.asInstanceOf[IOPromise[T]].complete(v)
  }

  object Fiber {
    private[kyo] def done[T](value: T): Fiber[T]             = value.asInstanceOf[Fiber[T]]
    private[kyo] def failed[T](reason: Throwable): Fiber[T]  = Failed(reason).asInstanceOf[Fiber[T]]
    private[kyo] def promise[T](p: IOPromise[T]): Promise[T] = p.asInstanceOf[Promise[T]]
    implicit def flat[T]: Flat[Fiber[T]]                     = Flat.unsafe.checked[Fiber[T]]
  }

  implicit class FiberOps[T](private val state: Fiber[T]) extends AnyVal {

    private implicit def flat[S]: Flat[T > S] = Flat.unsafe.checked[T > S]

    def isDone: Boolean > IOs =
      state match {
        case promise: IOPromise[_] =>
          IOs(promise.isDone())
        case _ =>
          true
      }

    def get: T > Fibers =
      state match {
        case promise: IOPromise[_] =>
          FiberGets(state)
        case failed: Failed =>
          FiberGets(state)
        case _ =>
          state.asInstanceOf[T > Fibers]
      }

    def onComplete(f: T > IOs => Unit): Unit > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.onComplete(f))
        case Failed(ex) =>
          f(IOs.fail(ex))
        case _ =>
          f(state.asInstanceOf[T > IOs])
      }

    def getTry: Try[T] > Fibers =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs {
            val p = new IOPromise[Try[T]]
            p.interrupts(promise)
            promise.onComplete { t =>
              p.complete(Try(IOs.run(t)))
            }
            Fibers.get(Fiber.promise(p))
          }
        case Failed(ex) =>
          Failure(ex)
        case _ =>
          Success(state.asInstanceOf[T])
      }

    def block: T > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.block())
        case Failed(ex) =>
          IOs.fail(ex)
        case _ =>
          state.asInstanceOf[T > IOs]
      }

    def interrupt: Boolean > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.interrupt())
        case _ =>
          false
      }

    def toFuture: Future[T] > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs {
            val p = scala.concurrent.Promise[T]()
            promise.onComplete { v =>
              p.complete(Try(IOs.run(v)))
            }
            p.future
          }
        case Failed(ex) =>
          Future.failed(ex)
        case _ =>
          Future.successful(state.asInstanceOf[T])
      }

    def transform[U](t: T => Fiber[U]): Fiber[U] > IOs =
      IOs(unsafeTransform(t))

    private[kyo] def unsafeTransform[U](t: T => Fiber[U]): Fiber[U] =
      state match {
        case promise: IOPromise[T] @unchecked =>
          val r = new IOPromise[U]()
          r.interrupts(promise)
          promise.onComplete { v =>
            try {
              t(IOs.run(v)) match {
                case v: IOPromise[U] @unchecked =>
                  r.become(v)
                case Failed(ex) =>
                  r.complete(IOs.fail(ex))
                case v =>
                  r.complete(v.asInstanceOf[U])
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                r.complete(IOs.fail(ex))
            }
          }
          Fiber.promise(r)
        case failed: Failed =>
          this.asInstanceOf[Fiber[U]]
        case _ =>
          try t(state.asInstanceOf[T])
          catch {
            case ex if (NonFatal(ex)) =>
              Fiber.failed(ex)
          }
      }
  }

  type Fibers >: Fibers.Effects <: Fibers.Effects

  object Fibers {

    type Effects = FiberGets with IOs

    case object Interrupted
        extends RuntimeException
        with NoStackTrace

    private[kyo] val interrupted = IOs.fail(Interrupted)

    def run[T](v: T > Fibers)(implicit f: Flat[T > Fibers]): Fiber[T] > IOs =
      FiberGets.run(v)

    def runBlocking[T, S](v: T > (Fibers with S))(implicit
        f: Flat[T > (Fibers with S)]
    ): T > (IOs with S) =
      FiberGets.runBlocking[T, S](v)

    def value[T](v: T)(implicit f: Flat[T > Any]): Fiber[T] =
      Fiber.done(v)

    def get[T, S](v: Fiber[T] > S): T > (Fibers with S) =
      v.map(_.get)

    private val _promise = IOs(unsafeInitPromise[Object])

    def initPromise[T]: Promise[T] > IOs =
      _promise.asInstanceOf[Promise[T] > IOs]

    private[kyo] def unsafeInitPromise[T]: Promise[T] =
      Fiber.promise(new IOPromise[T]())

    // compiler bug workaround
    private val IOTask = kyo.concurrent.scheduler.IOTask

    /*inline*/
    def init[T]( /*inline*/ v: => T > Fibers)(implicit f: Flat[T > Fibers]): Fiber[T] > IOs =
      Locals.save.map(st => Fiber.promise(IOTask(IOs(v), st)))

    /*inline*/
    def init[T, S](j: Joins[S])( /*inline*/ v: => T > (S with Fibers))(implicit
        f: Flat[T > (S with Fibers)]
    ): Fiber[j.M[T]] > (S with IOs) =
      j.save.map { st =>
        init(j.handle(st, v))
      }

    def never: Fiber[Unit] > IOs =
      IOs(Fiber.promise(new IOPromise[Unit]))

    def delay[T, S](d: Duration)(v: => T > S): T > (S with Fibers) =
      sleep(d).andThen(v)

    def sleep(d: Duration): Unit > Fibers =
      initPromise[Unit].map { p =>
        if (d.isFinite) {
          val run: Unit > IOs =
            IOs {
              IOTask(IOs(p.complete(())), Locals.State.empty)
              ()
            }
          Timers.schedule(d)(run).map { t =>
            IOs.ensure(t.cancel.unit)(p.get)
          }
        } else {
          p.get
        }
      }

    def timeout[T](d: Duration)(v: => T > Fibers)(implicit f: Flat[T > Fibers]): T > Fibers =
      init(v).map { f =>
        val timeout: Unit > IOs =
          IOs {
            IOTask(IOs(f.interrupt), Locals.State.empty)
            ()
          }
        Timers.schedule(d)(timeout).map { t =>
          IOs.ensure(t.cancel.unit)(f.get)
        }
      }

    def fromFuture[T, S](f: Future[T]): T > Fibers =
      Fibers.get(fromFutureFiber(f))

    def fromFutureFiber[T](f: Future[T]): Fiber[T] > IOs = {
      Locals.save.map { st =>
        IOs {
          val p = new IOPromise[T]()
          f.onComplete { r =>
            val io =
              IOs[Boolean, IOs] {
                r match {
                  case Success(v) =>
                    p.complete(v)
                  case Failure(ex) =>
                    p.complete(IOs.fail(ex))
                }
              }
            IOTask(io, st)
          }(ExecutionContext.parasitic)
          Fiber.promise(p)
        }
      }
    }

    def parallel[T](l: Seq[T > Fibers])(implicit f: Flat[T > Fibers]): Seq[T] > Fibers =
      l.size match {
        case 0 => Seq.empty
        case 1 => l(0).map(Seq(_))
        case _ =>
          Fibers.get(parallelFiber[T](l))
      }

    def parallel[T, S](j: Joins[S])(l: Seq[T > (S with Fibers)])(
        implicit f: Flat[T > Fibers]
    ): Seq[T] > (S with Fibers) =
      j.save.map { st =>
        j.handle(st, l).map(parallel(_)).map(j.resume)
      }

    def parallelFiber[T](l: Seq[T > Fibers])(implicit f: Flat[T > Fibers]): Fiber[Seq[T]] > IOs =
      l.size match {
        case 0 => Fiber.done(Seq.empty)
        case 1 => Fibers.run(l(0).map(Seq(_)))(Flat.unsafe.checked)
        case _ =>
          Locals.save.map { st =>
            IOs {
              val p       = new IOPromise[Seq[T]]
              val size    = l.size
              val results = (new Array[Any](size)).asInstanceOf[Array[T]]
              val pending = new AtomicInteger(size)
              var i       = 0
              foreach(l) { io =>
                val fiber = IOTask(IOs(io), st)
                p.interrupts(fiber)
                val j = i
                fiber.onComplete { r =>
                  try {
                    results(j) = IOs.run(r)(Flat.unsafe.checked)
                    if (pending.decrementAndGet() == 0) {
                      p.complete(ArraySeq.unsafeWrapArray(results))
                    }
                  } catch {
                    case ex if (NonFatal(ex)) =>
                      p.complete(IOs.fail(ex))
                  }
                }
                i += 1
              }
              Fiber.promise(p)
            }
          }
      }

    def race[T](l: Seq[T > Fibers])(implicit f: Flat[T > Fibers]): T > Fibers =
      l.size match {
        case 0 => IOs.fail("Can't race an empty list.")
        case 1 => l(0)
        case _ =>
          Fibers.get(raceFiber[T](l))
      }

    def race[T, S](j: Joins[S])(l: Seq[T > (S with Fibers)])(implicit
        f: Flat[T > (S with Fibers)]
    ): T > (S with Fibers) =
      j.save.map { st =>
        j.handle(st, l).map(race(_)).map(j.resume)
      }

    def raceFiber[T](l: Seq[T > Fibers])(implicit f: Flat[T > Fibers]): Fiber[T] > IOs =
      l.size match {
        case 0 => IOs.fail("Can't race an empty list.")
        case 1 => Fibers.run(l(0))
        case _ =>
          Locals.save.map { st =>
            IOs {
              val p = new IOPromise[T]
              foreach(l) { io =>
                val f = IOTask(IOs(io), st)
                p.interrupts(f)
                f.onComplete(p.complete(_))
              }
              Fiber.promise(p)
            }
          }
      }

    def parallelTraverse[T, U](v: Seq[T] > Fibers)(f: T => U > Fibers)(implicit
        flat: Flat[U > Fibers]
    ): Seq[U] > Fibers =
      v.map(_.map(f)).map(parallel[U](_))

    def race[T](
        v1: => T > Fibers,
        v2: => T > Fibers
    )(implicit f: Flat[T > Fibers]): T > Fibers =
      race(List(v1, v2))

    def race[T](
        v1: => T > Fibers,
        v2: => T > Fibers,
        v3: => T > Fibers
    )(implicit f: Flat[T > Fibers]): T > Fibers =
      race(List(v1, v2, v3))

    def race[T](
        v1: => T > Fibers,
        v2: => T > Fibers,
        v3: => T > Fibers,
        v4: => T > Fibers
    )(implicit f: Flat[T > Fibers]): T > Fibers =
      race(List(v1, v2, v3, v4))

    def race[T, S](j: Joins[S])(
        v1: => T > (S with Fibers),
        v2: => T > (S with Fibers)
    )(implicit f: Flat[T > (S with Fibers)]): T > (S with Fibers) =
      race(j)(List(v1, v2))

    def race[T, S](j: Joins[S])(
        v1: => T > (S with Fibers),
        v2: => T > (S with Fibers),
        v3: => T > (S with Fibers)
    )(implicit f: Flat[T > (S with Fibers)]): T > (S with Fibers) =
      race(j)(List(v1, v2, v3))

    def race[T, S](j: Joins[S])(
        v1: => T > (S with Fibers),
        v2: => T > (S with Fibers),
        v3: => T > (S with Fibers),
        v4: => T > (S with Fibers)
    )(implicit f: Flat[T > (S with Fibers)]): T > (S with Fibers) =
      race(j)(List(v1, v2, v3, v4))

    def parallel[T1, T2](
        v1: => T1 > Fibers,
        v2: => T2 > Fibers
    )(implicit
        f1: Flat[T1 > Fibers],
        f2: Flat[T2 > Fibers]
    ): (T1, T2) > Fibers =
      parallel(List(v1, v2))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2])
      )

    def parallel[T1, T2, T3](
        v1: => T1 > Fibers,
        v2: => T2 > Fibers,
        v3: => T3 > Fibers
    )(implicit
        f1: Flat[T1 > Fibers],
        f2: Flat[T2 > Fibers],
        f3: Flat[T3 > Fibers]
    ): (T1, T2, T3) > Fibers =
      parallel(List(v1, v2, v3))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def parallel[T1, T2, T3, T4](
        v1: => T1 > Fibers,
        v2: => T2 > Fibers,
        v3: => T3 > Fibers,
        v4: => T4 > Fibers
    )(implicit
        f1: Flat[T1 > Fibers],
        f2: Flat[T2 > Fibers],
        f3: Flat[T3 > Fibers],
        f4: Flat[T4 > Fibers]
    ): (T1, T2, T3, T4) > Fibers =
      parallel(List(v1, v2, v3, v4))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def parallel[T1, T2, S](j: Joins[S])(
        v1: => T1 > (S with Fibers),
        v2: => T2 > (S with Fibers)
    )(implicit
        f1: Flat[T1 > (S with Fibers)],
        f2: Flat[T2 > (S with Fibers)]
    ): (T1, T2) > (S with Fibers) =
      parallel(j)(List(v1, v2))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2])
      )

    def parallel[T1, T2, T3, S](j: Joins[S])(
        v1: => T1 > (S with Fibers),
        v2: => T2 > (S with Fibers),
        v3: => T3 > (S with Fibers)
    )(implicit
        f1: Flat[T1 > (S with Fibers)],
        f2: Flat[T2 > (S with Fibers)],
        f3: Flat[T3 > (S with Fibers)]
    ): (T1, T2, T3) > (S with Fibers) =
      parallel(j)(List(v1, v2, v3))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def parallel[T1, T2, T3, T4, S](j: Joins[S])(
        v1: => T1 > (S with Fibers),
        v2: => T2 > (S with Fibers),
        v3: => T3 > (S with Fibers),
        v4: => T4 > (S with Fibers)
    )(implicit
        f1: Flat[T1 > (S with Fibers)],
        f2: Flat[T2 > (S with Fibers)],
        f3: Flat[T3 > (S with Fibers)],
        f4: Flat[T4 > (S with Fibers)]
    ): (T1, T2, T3, T4) > (S with Fibers) =
      parallel(List(v1, v2, v3, v4))(Flat.unsafe.checked).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    /*inline*/
    private def foreach[T, U](l: Seq[T])( /*inline*/ f: T => Unit): Unit = {
      val it = l.iterator
      while (it.hasNext) {
        f(it.next())
      }
    }
  }

  object internal {
    final class FiberGets private[fibers] () extends Effect[Fiber, FiberGets] {

      def apply[T, S](f: Fiber[T] > S): T > (FiberGets with S) =
        suspend(f)

      def run[T](v: T > Fibers)(implicit f: Flat[T > Fibers]): Fiber[T] > IOs = {
        implicit val handler: DeepHandler[Fiber, FiberGets] =
          new DeepHandler[Fiber, FiberGets] {
            def pure[T](v: T) = Fiber.done(v)
            def apply[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
              m.unsafeTransform(f)
          }
        IOs(deepHandle[Fiber, FiberGets, T](FiberGets)(IOs.runLazy(v)))
      }

      def runBlocking[T, S](v: T > (Fibers with S))(implicit
          f: Flat[T > (Fibers with S)]
      ): T > (IOs with S) = {
        implicit def handler: Handler[Fiber, FiberGets, Any] =
          new Handler[Fiber, FiberGets, Any] {
            def pure[T](v: T) = Fiber.done(v)
            override def handle[T](ex: Throwable): T > FiberGets =
              FiberGets(Fiber.failed[T](ex))
            def apply[T, U, S](m: Fiber[T], f: T => U > (FiberGets with S)) =
              try {
                m match {
                  case m: IOPromise[T] @unchecked =>
                    f(m.block())
                  case Failed(ex) =>
                    handle(ex)
                  case _ =>
                    f(m.asInstanceOf[T])
                }
              } catch {
                case ex if (NonFatal(ex)) =>
                  handle(ex)
              }
          }
        IOs[T, S](handle[T, IOs with S, Any](v).map(_.block))
      }
    }
    val FiberGets = new FiberGets
  }
}
