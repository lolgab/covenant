package covenant.ws

import sloth._
import covenant.ws.api._
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import chameleon._
import cats.data.EitherT

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import monix.execution.Scheduler

import scala.concurrent.Future

trait AkkaHttpRouteImplicits {
  def routerAsWsRoute[PickleType, Result[_], Event, ErrorType, State](router: Router[PickleType, Result], config: WebsocketServerConfig, handler: RequestHandler[PickleType, Event, ErrorType, State])(implicit
    system: ActorSystem,
    serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType],
    builder: AkkaMessageBuilder[PickleType]): Route = {

    val websocketServer = WebsocketServer[PickleType, Event, ErrorType, State](config, handler)
    get {
      handleWebSocketMessages(websocketServer.flow())
    }
  }

  implicit class WsRouter[PickleType, Result[_]](router: Router[PickleType, Result]) {
    def asWsRoute[Event, ErrorType, State](config: WebsocketServerConfig, handler: RequestHandler[PickleType, Event, ErrorType, State])(implicit
      system: ActorSystem,
      serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
      deserializer: Deserializer[ClientMessage[PickleType], PickleType],
      builder: AkkaMessageBuilder[PickleType]): Route = routerAsWsRoute(router, config, handler)

    def asWsRoute[Event, ErrorType, State](
      config: WebsocketServerConfig,
      api: ApiConfiguration[Event, ErrorType, State]
    )(implicit
      ev: Result[_] =:= api.dsl.ApiFunction[_],
      scheduler: Scheduler,
      system: ActorSystem,
      serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
      deserializer: Deserializer[ClientMessage[PickleType], PickleType],
      builder: AkkaMessageBuilder[PickleType]) = {

      val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](
        api, router.asInstanceOf[Router[PickleType, Dsl[Event, ErrorType, State]#ApiFunction]])

      routerAsWsRoute(router, config, handler)
    }
  }

  implicit class WsRouterFuture[PickleType](router: Router[PickleType, Future]) {
    def asWsRoute[ErrorType](
      config: WebsocketServerConfig,
      failedRequestError: ServerFailure => ErrorType,
      recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty)(implicit
      system: ActorSystem,
      serializer: Serializer[ServerMessage[PickleType, Unit, ErrorType], PickleType],
      deserializer: Deserializer[ClientMessage[PickleType], PickleType],
      builder: AkkaMessageBuilder[PickleType]): Route = {
      import system.dispatcher

      val handler = new SimpleStatelessRequestHandler[PickleType, Unit, ErrorType] {
        override def onClientConnect(): Unit = {
          scribe.info("Client connected")
        }
        override def onClientDisconnect(reason: DisconnectReason): Unit = {
          scribe.info(s"Client disconnected: $reason")
        }
        override def onRequest(path: List[String], payload: PickleType): Response = {
          router(Request(path, payload)).toEither match {
            case Right(res) =>
              val recoveredResult = res.map(Right(_)).recover(recover andThen Left.apply)
              Response(recoveredResult.map(ReturnValue(_)))
            case Left(err) => Response(Future.successful(ReturnValue(Left(failedRequestError(err)))))
          }
        }
      }

      routerAsWsRoute[PickleType, Future, Unit, ErrorType, Unit](router, config, handler)
    }
  }

  implicit class WsRouterEitherT[PickleType, ErrorType](router: Router[PickleType, EitherT[Future, ErrorType, ?]]) {
    def asWsRoute(
      config: WebsocketServerConfig,
      failedRequestError: ServerFailure => ErrorType,
      recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty)(implicit
      system: ActorSystem,
      serializer: Serializer[ServerMessage[PickleType, Unit, ErrorType], PickleType],
      deserializer: Deserializer[ClientMessage[PickleType], PickleType],
      builder: AkkaMessageBuilder[PickleType]): Route = {
      import system.dispatcher

      val handler = new SimpleStatelessRequestHandler[PickleType, Unit, ErrorType] {
        override def onClientConnect(): Unit = {
          scribe.info("Client connected")
        }
        override def onClientDisconnect(reason: DisconnectReason): Unit = {
          scribe.info(s"Client disconnected: $reason")
        }
        override def onRequest(path: List[String], payload: PickleType): Response = {
          router(Request(path, payload)).toEither match {
            case Right(res) =>
              val recoveredResult = res.value.recover(recover andThen Left.apply)
              Response(recoveredResult.map(ReturnValue(_)))
            case Left(err) => Response(Future.successful(ReturnValue(Left(failedRequestError(err)))))
          }
        }
      }

      routerAsWsRoute[PickleType, EitherT[Future, ErrorType, ?], Unit, ErrorType, Unit](router, config, handler)
    }
  }


  // implicit class WsRouterApi[PickleType, Event, ErrorType, State](router: Router[PickleType, Dsl[Event, ErrorType, State]#ApiFunction]) {
    // def asWsRoute(
    //   config: WebsocketServerConfig,
    //   api: ApiConfiguration[Event, ErrorType, State]
    // )(implicit
    //   scheduler: Scheduler,
    //   system: ActorSystem,
    //   serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
    //   deserializer: Deserializer[ClientMessage[PickleType], PickleType],
    //   builder: AkkaMessageBuilder[PickleType]) = {

    //   val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](api, router.asInstanceOf[Router[PickleType, Dsl[Event, ErrorType, State]#ApiFunction]])

    //   routerAsWsRoute(router, config, handler)
    // }
  // }
}
