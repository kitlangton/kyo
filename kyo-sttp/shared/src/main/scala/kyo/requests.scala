package kyo

import kyo._
import kyo.concurrent.fibers._
import kyo.envs._
import kyo.joins._
import kyo.ios._
import sttp.client3._

object requests {

  abstract class Backend {
    def send[T](r: Request[T, Any]): Response[T] > Fibers
  }

  object Backend {
    implicit val default: Backend = PlatformBackend.default
  }

  type Requests >: Requests.Effects <: Requests.Effects

  object Requests {

    type Effects = Envs[Backend] with Fibers

    private val envs = Envs[Backend]

    def run[T, S](b: Backend)(v: T > (Requests with S))(implicit
        f: Flat[T > (Requests with S)]
    ): T > (Fibers with S) =
      envs.run[T, Fibers with S](b)(v)

    def run[T, S](v: T > (Requests with S))(implicit
        b: Backend,
        f: Flat[T > (Requests with S)]
    ): T > (Fibers with S) =
      run[T, S](b)(v)

    type BasicRequest = sttp.client3.RequestT[Empty, Either[_, String], Any]

    val basicRequest: BasicRequest =
      sttp.client3.basicRequest.mapResponse {
        case Left(s) =>
          Left(new Exception(s))
        case Right(v) =>
          Right(v)
      }

    def apply[T](f: BasicRequest => Request[Either[_, T], Any]): T > Requests =
      request(f(basicRequest))

    def request[T](req: Request[Either[_, T], Any]): T > Requests =
      envs.get.map(_.send(req)).map {
        _.body match {
          case Left(ex: Throwable) =>
            IOs.fail[T](ex)
          case Left(ex) =>
            IOs.fail[T](new Exception("" + ex))
          case Right(value) =>
            value
        }
      }

    // implicit def joinsRequests: Joins[Requests] =
    //   new Joins[Requests] {

    //     type M[T]  = T
    //     type State = Backend
    //     def save = envs.get

    //     def handle[T, S](s: Backend, v: T > (Requests with S))(
    //         implicit f: Flat[T > (Requests with S)]
    //     ) =
    //       Requests.run(s)(v)

    //     def resume[T, S](v: Nothing > S): T > (Requests with S) =
    //       v
    //   }
  }
}
