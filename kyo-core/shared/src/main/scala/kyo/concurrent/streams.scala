// package kyo.concurrent

// import kyo._
// import kyo.aborts._
// import kyo.core._
// import kyo.ios._
// import kyo.concurrent.fibers._
// import kyo.concurrent.channels._

// object streams {

//   import internal._

//   type Streams = Sources with Fibers with IOs

//   object Streams {

//     def source[T](buffer: Int)(f: => Option[T]): T > Streams =
//       Channels.init[T](buffer).map { ch =>
//         def loop(): Unit > (IOs with Fibers) =
//           f match {
//             case None =>
//             case Some(v) =>
//               ch.put(v).andThen(loop())
//           }
//         Fibers.forkFiber(loop()).map { p =>
//           Sources[T](ch, p)
//         }
//       }
//     def take[T, S](n: Int)(v: T > (S with Streams)): T > (S with Streams) =
//       ???
//     def buffer[T, S](n: Int)(v: T > (S with Streams)): Seq[T] > (S with Streams) =
//       ???
//   }

//   object internal {
//     case class Source[T](channel: Channel[T], producer: Fiber[Unit])
//     final class Sources extends Effect[Source, Sources] {
//       def apply[T](channel: Channel[T], producer: Fiber[Unit]): T > Sources =
//         suspend(Source(channel, producer))
//     }
//     val Sources = new Sources
//   }
// }
