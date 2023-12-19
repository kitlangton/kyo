package kyo.llm

import kyo._
import kyo.llm.completions._
import kyo.llm.configs._
import kyo.llm.contexts._
import kyo.llm.agents._
import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.seqs._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import zio.schema.codec.JsonCodec

import java.lang.ref.WeakReference
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import kyo.consoles.Consoles

object ais {

  import internal._

  type AIs >: AIs.Effects <: AIs.Effects

  type desc = kyo.llm.desc
  val desc = kyo.llm.desc

  implicit class PromptInterpolator(val sc: StringContext) extends AnyVal {
    def p(args: Any*): String =
      sc.s(args: _*)
        .replaceAll("\n\\s+", "\n") // remove whitespace at the start of a line
        .trim
  }

  class AI private[ais] (val id: Long) {

    private val ref = new AIRef(this)

    def save: Context > AIs =
      State.get.map(_.getOrElse(ref, Context.empty))

    def dump: Unit > (AIs with Consoles) =
      save.map(_.dump).map(Consoles.println(_))

    def restore(ctx: Context): Unit > AIs =
      State.update(_ + (ref -> ctx)).unit

    def update(f: Context => Context): Unit > AIs =
      save.map { ctx =>
        restore(f(ctx))
      }

    def copy: AI > AIs =
      for {
        ai <- AIs.init
        _  <- State.update(st => st + (ai.ref -> st.getOrElse(ref, Context.empty)))
      } yield ai

    def seed[S](msg: String): Unit > AIs =
      update(_.seed(msg))

    def seed[S](msg: String, reminder: String): Unit > AIs =
      update(_.seed(msg).reminder(reminder))

    def reminder[S](msg: String): Unit > AIs =
      update(_.reminder(msg))

    def userMessage(msg: String, imageUrls: List[String] = Nil): Unit > AIs =
      update(_.userMessage(msg, imageUrls))

    def systemMessage(msg: String): Unit > AIs =
      update(_.systemMessage(msg))

    def assistantMessage(msg: String, calls: List[Call] = Nil): Unit > AIs =
      update(_.assistantMessage(msg, calls))

    def agentMessage(callId: CallId, msg: String): Unit > AIs =
      update(_.agentMessage(callId, msg))

    def ask(msg: String): String > AIs =
      userMessage(msg).andThen(ask)

    def ask: String > AIs = {
      def eval(agents: Set[Agent]): String > AIs =
        fetch(agents).map { r =>
          r.calls match {
            case Nil =>
              r.content
            case calls =>
              Agents.handle(this, agents, calls)
                .andThen(eval(agents))
          }
        }
      Agents.get.map(eval)
    }

    def gen[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      userMessage(msg).andThen(gen[T])

    def gen[T](implicit t: ValueSchema[T]): T > AIs = {
      Agents.resultAgent[T].map { case (resultAgent, result) =>
        def eval(): T > AIs =
          fetch(Set(resultAgent), Some(resultAgent)).map { r =>
            Agents.handle(this, Set(resultAgent), r.calls).andThen {
              result.map {
                case Some(v) =>
                  v
                case None =>
                  eval()
              }
            }
          }
        eval()
      }
    }

    def infer[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      userMessage(msg).andThen(infer[T])

    def infer[T](implicit t: ValueSchema[T]): T > AIs = {
      Agents.resultAgent[T].map { case (resultAgent, result) =>
        def eval(agents: Set[Agent], constrain: Option[Agent] = None): T > AIs =
          fetch(agents, constrain).map { r =>
            r.calls match {
              case Nil =>
                eval(agents, Some(resultAgent))
              case calls =>
                Agents.handle(this, agents, calls).andThen {
                  result.map {
                    case None =>
                      eval(agents)
                    case Some(v) =>
                      v
                  }
                }
            }
          }
        Agents.get.map(p => eval(p + resultAgent))
      }
    }

    private def fetch(
        agents: Set[Agent],
        constrain: Option[Agent] = None
    ): Completions.Result > AIs =
      for {
        ctx <- save
        r   <- Completions(ctx, agents, constrain)
        _   <- assistantMessage(r.content, r.calls)
      } yield r
  }

  object AIs {

    type Effects = Sums[State] with Requests

    case class AIException(cause: String) extends Exception(cause) with NoStackTrace

    private val nextId = IOs.run(Atomics.initLong(0))

    val configs = Configs

    val init: AI > AIs =
      nextId.incrementAndGet.map(new AI(_))

    def init(seed: String): AI > AIs =
      init.map { ai =>
        ai.seed(seed).andThen(ai)
      }

    def init(seed: String, reminder: String): AI > AIs =
      init(seed).map { ai =>
        ai.reminder(reminder).andThen(ai)
      }

    def run[T, S](v: T > (AIs with S))(implicit f: Flat[T > AIs with S]): T > (Requests with S) =
      State.run[T, Requests with S](v).map(_._1)

    def ask(msg: String): String > AIs =
      init.map(_.ask(msg))

    def gen[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init.map(_.gen[T](msg))

    def infer[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init.map(_.infer[T](msg))

    def ask(seed: String, msg: String): String > AIs =
      init(seed).map(_.ask(msg))

    def gen[T](seed: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed).map(_.gen[T](msg))

    def infer[T](seed: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed).map(_.infer[T](msg))

    def ask(seed: String, reminder: String, msg: String): String > AIs =
      init(seed, reminder).map(_.ask(msg))

    def gen[T](seed: String, reminder: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed, reminder).map(_.gen[T](msg))

    def infer[T](seed: String, reminder: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed, reminder).map(_.infer[T](msg))

    def restore(ctx: Context): AI > AIs =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T](cause: String): T > AIs =
      IOs.fail(AIException(cause))

    def ephemeral[T, S](f: => T > S)(implicit flat: Flat[T > S]): T > (AIs with S) =
      State.get.map { st =>
        Tries.run[T, S](f).map(r => State.set(st).map(_ => r.get))
      }
  }

  object internal {

    type State = Map[AIRef, Context]

    val State = Sums[State]

    class AIRef(ai: AI) extends WeakReference[AI](ai) {

      private val id = ai.id

      def isValid(): Boolean = get() != null

      override def equals(obj: Any): Boolean =
        obj match {
          case other: AIRef => id == other.id
          case _            => false
        }

      override def hashCode =
        (31 * id.toInt) + 31
    }

    implicit val summer: Summer[State] =
      new Summer[State] {
        val init = Map.empty
        def add(x: State, y: State) = {
          val merged = x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context.empty) ++ v) }
          merged.filter { case (k, v) => k.isValid() && !v.isEmpty }
        }
      }
  }
}
