package kyo

import izumi.reflect.*
import kyo.core.*
import scala.annotation.implicitNotFound

opaque type Envs[-T] = IOs

object Envs:

    private val local = Locals.init(Map.empty[Tag[_], Any])

    final case class EnvsDsl[E](tag: Tag[E]):
        self =>

        def let[T, E1, S1, S2](e: E < S1)(
            v: T < (Envs[E | E1] & S2)
        ): T < (Envs[E1] & S1 & S2) =
            e.map(e => local.update(_ + (tag -> e))(v))

        def use[T, E1, S](f: E => T < S): T < (Envs[E] & S) =
            get.map(f)

        def get: E < Envs[E] =
            local.get.map(_.getOrElse(
                tag,
                bug("Missing environment value: " + tag)
            ).asInstanceOf[E])

        def layer[S](construct: E < S): Layer[Envs[E], S & IOs] =
            new Layer[Envs[E], S & IOs]:
                override def run[T, S1](effect: T < (Envs[E] & S1))(
                    implicit fl: Flat[T < (Envs[E] & S1)]
                ): T < (S & S1 & IOs) =
                    construct.map(e => Envs.run(self.let(e)(effect)))

    end EnvsDsl

    def apply[E](using tag: Tag[E]) = EnvsDsl[E](tag)

    def run[T, S, E](v: T < (Envs[E] & S))(
        using
        @implicitNotFound(
            "Pending: '${E}'. Use 'let' to satisfy the missing requirements: 'Envs[YourType].let(value)(computation)'"
        ) ev: E => Nothing
    ): T < (IOs with S) =
        v
end Envs
