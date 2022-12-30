package kyo.scheduler

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import scala.annotation.tailrec
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicReference

import kyo.core._
import kyo.ios._
import java.util.concurrent.atomic.LongAdder

import kyo.scheduler.IOTask

object Scheduler {

  private val coreWorkers = Runtime.getRuntime().availableProcessors()

  @volatile
  private var concurrencyLimit = coreWorkers
  private val concurrency      = AtomicInteger(0)

  val workers      = CopyOnWriteArrayList[Worker]
  private val idle = AtomicReference[List[Worker]](Nil)
  private val pool = Executors.newCachedThreadPool(ThreadFactory("kyo-worker", new Worker(_)))

  startWorkers()

  def removeWorker(): Unit =
    concurrencyLimit = Math.max(1, concurrency.get() - 1)

  def addWorker(): Unit =
    concurrencyLimit = Math.max(concurrencyLimit, concurrency.get()) + 1
    startWorkers()

  private def startWorkers(): Unit =
    var c = concurrency.get()
    while (c < concurrencyLimit && concurrency.compareAndSet(c, c + 1)) {
      pool.execute(() => Worker().runWorker(null))
      c = concurrency.get()
    }

  def schedule[T](t: IOTask[_]): Unit =
    val w = Worker()
    if (w != null) {
      w.enqueueLocal(t)
    } else {
      submit(t)
    }

  @tailrec def submit(t: IOTask[_]): Unit =

    val iw = idle.get()
    if ((iw ne Nil) && idle.compareAndSet(iw, iw.tail)) {
      val w  = iw.head
      val ok = w.enqueue(t)
      if (ok) {
        return
      }
    }

    var w0: Worker = randomWorker()
    var w1: Worker = randomWorker()
    if (w0.load() > w1.load()) {
      val w = w0
      w0 = w1
      w1 = w
    }
    if (!w0.enqueue(t) && !w1.enqueue(t)) {
      submit(t)
    }

  def steal(w: Worker): IOTask[_] = {
    var r: IOTask[_] = null
    var w0: Worker    = randomWorker()
    var w1: Worker    = randomWorker()
    if (w0.load() < w1.load()) {
      val w = w0
      w0 = w1
      w1 = w
    }
    r = w0.steal(w)
    if (r == null) {
      r = w1.steal(w)
    }
    r
  }

  def loadAvg(): Double =
    var sum = 0L
    val it  = workers.iterator()
    var c   = 0
    while (it.hasNext()) {
      sum += it.next().load()
      c += 1
    }
    sum.doubleValue() / c

  def idle(w: Worker): Unit =
    var i = idle.get()
    if (w.load() == 0 && idle.compareAndSet(i, w :: i)) {
      w.park()
    }

  def stopWorker(): Boolean =
    val c = concurrency.get()
    c > concurrencyLimit && concurrency.compareAndSet(c, c - 1)

  @tailrec private def randomWorker(): Worker =
    try {
      workers.get(XSRandom.nextInt(workers.size()))
    } catch {
      case _: ArrayIndexOutOfBoundsException | _: IllegalArgumentException =>
        randomWorker()
    }

  override def toString =
    import scala.jdk.CollectionConverters._
    val w = workers.asScala.map(_.toString).mkString("\n")
    s"$w\nScheduler(loadAvg=${loadAvg()},concurrency=$concurrency,limit=$concurrencyLimit)"

}