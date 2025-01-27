package kyo

import core.*
import core.internal.*
import kyo.iosInternal.*

abstract class Local[T]:

    import Locals.*

    def default: T

    val get: T < IOs =
        new KyoIO[T, Any]:
            def apply(v: Unit < (IOs & Any), s: Safepoint[IO, IOs], l: State) =
                get(l)

    def let[U, S](f: T)(v: U < S): U < (S & IOs) =
        def letLoop(f: T, v: U < S): U < S =
            v match
                case kyo: Kyo[MX, EX, Any, U, S] @unchecked =>
                    new KyoCont[MX, EX, Any, U, S](kyo):
                        def apply(v2: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
                            letLoop(f, kyo(v2, s, l.updated(Local.this, f)))
                case _ =>
                    v
        letLoop(f, v)
    end let

    def update[U, S](f: T => T)(v: U < S): U < (S & IOs) =
        def updateLoop(f: T => T, v: U < S): U < S =
            v match
                case kyo: Kyo[MX, EX, Any, U, S] @unchecked =>
                    new KyoCont[MX, EX, Any, U, S](kyo):
                        def apply(v2: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
                            updateLoop(f, kyo(v2, s, l.updated(Local.this, f(get(l)))))
                case _ =>
                    v
        updateLoop(f, v)
    end update

    private def get(l: Locals.State) =
        l.getOrElse(Local.this, default).asInstanceOf[T]
end Local

object Locals:

    type State = Map[Local[_], Any]

    object State:
        val empty: State = Map.empty

    def init[T](defaultValue: T): Local[T] =
        new Local[T]:
            def default = defaultValue

    val save: State < IOs =
        new KyoIO[State, Any]:
            def apply(v: Unit < (IOs & Any), s: Safepoint[IO, IOs], l: Locals.State) =
                l

    def restore[T, S](st: State)(f: T < S): T < (IOs & S) =
        def loop(f: T < S): T < S =
            f match
                case kyo: Kyo[MX, EX, Any, T, S] @unchecked =>
                    new KyoCont[MX, EX, Any, T, S](kyo):
                        def apply(v2: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
                            loop(kyo(v2, s, l ++ st))
                case _ =>
                    f
        loop(f)
    end restore
end Locals
