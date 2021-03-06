package covenant.http

import sloth._
import covenant.core.DefaultLogHandler

import org.scalajs.dom
import scala.scalajs.js.typedarray.ArrayBuffer

import cats.data.EitherT
import cats.implicits._

import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.util.Try

private[http] trait NativeHttpClient {
  def apply[PickleType](
    baseUri: String,
    logger: LogHandler[Future]
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]): Client[PickleType, Future, ClientException] = {

    val transport = new RequestTransport[PickleType, Future] {
      private val sender = sendRequest[PickleType, Exception](baseUri, (r,c) => new Exception(s"Http request failed $r: $c")) _
      def apply(request: Request[PickleType]): Future[PickleType] = {
        sender(request).flatMap {
          case Right(res) => Future.successful(res)
          case Left(err) => Future.failed(err)
        }
      }
    }

    Client[PickleType, Future, ClientException](transport, logger)
  }
  def apply[PickleType](
    baseUri: String
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]): Client[PickleType, Future, ClientException] = {
      apply[PickleType](baseUri, new DefaultLogHandler[Future](identity))
    }

  def apply[PickleType, ErrorType : ClientFailureConvert](
    baseUri: String,
    failedRequestError: (String, Int) => ErrorType,
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty,
    logger: LogHandler[EitherT[Future, ErrorType, ?]] = null
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]): Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType] = {

    val transport = new RequestTransport[PickleType, EitherT[Future, ErrorType, ?]] {
      private val sender = sendRequest[PickleType, ErrorType](baseUri, failedRequestError) _
      def apply(request: Request[PickleType]) = EitherT[Future, ErrorType, PickleType] {
        sender(request).recover(recover andThen (Left(_)))
      }
    }

    Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType](transport, if (logger == null) new DefaultLogHandler[EitherT[Future, ErrorType, ?]](_.value) else logger)
  }

  private def sendRequest[PickleType, ErrorType](
    baseUri: String,
    failedRequestError: (String, Int) => ErrorType
  )(request: Request[PickleType])(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]) = {

    val uri = (baseUri :: request.path).mkString("/")
    val promise = Promise[Either[ErrorType, PickleType]]

    //TODO use fetch? can be intercepted by serviceworker.
    val http = new dom.XMLHttpRequest
    http.responseType = builder.responseType
    def failedRequest = failedRequestError(uri, http.status)

    http.open("POST", uri, true)
    http.onreadystatechange = { (_: dom.Event) =>
      if(http.readyState == 4)
        if (http.status == 200) {
          val value = (http.response: Any) match {
            case s: String => builder.unpack(s).map(_.toRight(failedRequest))
            case a: ArrayBuffer => builder.unpack(a).map(_.toRight(failedRequest))
            case b: dom.Blob => builder.unpack(b).map(_.toRight(failedRequest))
            case _ => Future.successful(Left(failedRequest))
          }
          promise completeWith value
        }
        else promise trySuccess Left(failedRequest)
    }

    val message = builder.pack(request.payload)
    (message: Any) match {
      case s: String => Try(http.send(s))
      case a: ArrayBuffer => Try(http.send(a))
      case b: dom.Blob => Try(http.send(b))
    }

    promise.future
  }
}
