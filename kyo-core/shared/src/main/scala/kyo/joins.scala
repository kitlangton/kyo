package kyo

import kyo._
import kyo.core._
import kyo.ios._
import kyo.aborts._
import kyo.envs._
import kyo.seqs._
import izumi.reflect._
import kyo.resources.Resources

object joins {

  trait Joins[E] { self =>

    type M[_]
    type State

    implicit def flat[T]: Flat[M[T]] = Flat.unsafe.unchecked

    def save: State > E

    def handle[T, S](
        s: State,
        v: T > (E with S)
    )(implicit
        f: Flat[T > (E with S)]
    ): M[T] > (S with IOs)

    def resume[T, S](v: M[T] > S): T > (E with S)

    def handle[T, S](
        s: State,
        l: Seq[T > (E with S)]
    )(implicit
        f: Flat[T > (E with S)]
    ): Seq[M[T]] > (S with IOs) =
      Seqs.traverse(l.toList)(handle(s, _))

    def resume[T, S](l: Seq[M[T] > S]): Seq[T] > (E with S with IOs) =
      Seqs.collect(l.toList).map { ls =>
        Seqs.traverse(ls)(resume)
      }

    def andThen[E2](j: Joins[E2]): Joins[E with E2] =
      new Joins[E with E2] {
        type M[T]  = j.M[self.M[T]]
        type State = (j.State, self.State)

        def save = zip(j.save, self.save)

        def handle[T, S](
            s: State,
            v: T > (E & E2 & S)
        )(implicit
            f: Flat[T > (E & E2 & S)]
        ) =
          j.handle(s._1, self.handle(s._2, v))

        def resume[T, S](v: M[T] > S) =
          self.resume(j.resume(v))
      }
  }

  object Joins {
    def apply[E1: Joins]: Joins[E1] =
      implicitly[Joins[E1]]

    def apply[E1: Joins, E2: Joins]: Joins[E1 with E2] =
      Joins[E1].andThen(Joins[E2])

    def apply[E1: Joins, E2: Joins, E3: Joins]: Joins[E1 with E2 with E3] =
      Joins[E1].andThen(Joins[E2]).andThen(Joins[E3])

    def apply[E1: Joins, E2: Joins, E3: Joins, E4: Joins]: Joins[E1 with E2 with E3 with E4] =
      Joins[E1].andThen(Joins[E2]).andThen(Joins[E3]).andThen(Joins[E4])

    def apply[E1: Joins, E2: Joins, E3: Joins, E4: Joins, E5: Joins]
        : Joins[E1 with E2 with E3 with E4 with E5] =
      Joins[E1].andThen(Joins[E2]).andThen(Joins[E3]).andThen(Joins[E4]).andThen(Joins[E5])

    implicit def aborts[E: Tag]: Joins[Aborts[E]] =
      new Joins[Aborts[E]] {
        type State = Unit
        type M[T]  = Abort[E]#Value[T]
        val aborts = Aborts[E]

        def save = ()
        def handle[T, S](s: State, v: T > (Aborts[E] & S))(implicit f: Flat[T > (Aborts[E] & S)]) =
          aborts.run(v)
        def resume[T, S](v: M[T] > S) =
          aborts.get(v)
      }

    implicit def envs[E: Tag]: Joins[Envs[E]] =
      new Joins[Envs[E]] {
        type State = E
        type M[T]  = T
        val envs = Envs[E]

        def save = envs.get
        def handle[T, S](s: State, v: T > (Envs[E] & S))(implicit f: Flat[T > (Envs[E] & S)]) =
          envs.run(s)(v)
        def resume[T, S](v: M[T] > S) =
          v
      }

    implicit val lists: Joins[Seqs] =
      new Joins[Seqs] {
        type State = Unit
        type M[T]  = Seq[T]

        def save = ()
        def handle[T, S](s: Unit, v: T > (Seqs & S))(implicit f: Flat[T > (Seqs & S)]) =
          Seqs.run(v)
        def resume[T, S](v: Seq[T] > S): T > (Seqs & S) =
          Seqs.get(v)
      }

  }
}
