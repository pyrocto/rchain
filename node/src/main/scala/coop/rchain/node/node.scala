package coop.rchain.node

import java.security.cert.X509Certificate

import cats._
import cats.data.EitherT.{leftT, rightT}
import cats.data._
import cats.effect._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.effect.concurrent.Ref
import coop.rchain.blockstorage.BlockStore.BlockHash
import coop.rchain.blockstorage.{BlockStore, InMemBlockStore}
import coop.rchain.blockstorage.{BlockStore, LMDBBlockStore}
import coop.rchain.casper.MultiParentCasperRef.MultiParentCasperRef
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.util.comm.CasperPacketHandler
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.{LastApprovedBlock, MultiParentCasper, MultiParentCasperRef, SafetyOracle}
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.catscontrib._
import coop.rchain.comm.CommError.ErrorHandler
import coop.rchain.comm._
import coop.rchain.comm.discovery._
import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import coop.rchain.comm.rp._
import coop.rchain.comm.transport._
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.util.CertificateHelper
import coop.rchain.metrics.Metrics
import coop.rchain.node.api._
import coop.rchain.node.configuration.Configuration
import coop.rchain.node.diagnostics._
import coop.rchain.p2p.effects._
import coop.rchain.rholang.interpreter.Runtime
import coop.rchain.shared._

import kamon._
import kamon.zipkin.ZipkinReporter
import io.grpc.Server
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.server.{Server => Http4sServer}
import org.http4s.server.blaze._

import scala.concurrent.duration._

class InitNodeRuntime(conf: Configuration, host: String, scheduler: Scheduler) {
  private implicit val log = effects.log

  private val dataDirFile = conf.server.dataDir.toFile

  def init: Effect[NodeRuntime] =
    for {
      _ <- canCreateDataDir
      _ <- Log[Effect].info(s"Using data_dir: ${dataDirFile.getAbsolutePath}")
      _ <- transport.generateCertificateIfAbsent[Effect].apply(conf.tls)
      _ <- hasCertificate
      _ <- hasKey
      n <- name
    } yield new NodeRuntime(conf, host, scheduler, n)

  private def isValid(pred: Boolean, msg: String): Effect[Unit] =
    if (pred) Left[CommError, Unit](InitializationError(msg)).toEitherT
    else Right(()).toEitherT

  private def name: Effect[String] = {
    val certificate: Effect[X509Certificate] =
      Task
        .delay(CertificateHelper.fromFile(conf.tls.certificate.toFile))
        .attemptT
        .leftMap(e => InitializationError(s"Failed to read the X.509 certificate: ${e.getMessage}"))

    for {
      cert <- certificate
      pk   = cert.getPublicKey
      name <- certBase16(CertificateHelper.publicAddress(pk))
    } yield name
  }

  def certBase16(maybePubAddr: Option[Array[Byte]]): Effect[String] =
    maybePubAddr match {
      case Some(bytes) =>
        rightT(
          Base16.encode(
            CertificateHelper.publicAddress(bytes)
          )
        )
      case None =>
        leftT(
          InitializationError(
            "Certificate must contain a secp256r1 EC Public Key"
          )
        )
    }

  def canCreateDataDir: Effect[Unit] = isValid(
    !dataDirFile.exists() && !dataDirFile.mkdir(),
    s"The data dir must be a directory and have read and write permissions:\\n${dataDirFile.getAbsolutePath}"
  )

  def haveAccessToDataDir: Effect[Unit] = isValid(
    !dataDirFile.isDirectory || !dataDirFile.canRead || !dataDirFile.canWrite,
    s"The data dir must be a directory and have read and write permissions:\n${dataDirFile.getAbsolutePath}"
  )

  def hasCertificate: Effect[Unit] = isValid(
    !conf.tls.certificate.toFile.exists(),
    s"Certificate file ${conf.tls.certificate} not found"
  )

  def hasKey: Effect[Unit] = isValid(
    !conf.tls.key.toFile.exists(),
    s"Secret key file ${conf.tls.certificate} not found"
  )
}

private class NodeRuntime(
    conf: Configuration,
    host: String,
    scheduler: Scheduler,
    name: String
)(
    implicit log: Log[Task]
) {

  private val loopScheduler = Scheduler.fixedPool("loop", 4)
  private val grpcScheduler = Scheduler.cached("grpc-io", 4, 64)

  private implicit val logSource: LogSource = LogSource(this.getClass)

  implicit def eiterTrpConfAsk(implicit ev: RPConfAsk[Task]): RPConfAsk[Effect] =
    new EitherTApplicativeAsk[Task, RPConf, CommError]

  import ApplicativeError_._

  /** Configuration */
  private val port              = conf.server.port
  private val kademliaPort      = conf.server.kademliaPort
  private val address           = s"rnode://$name@$host?protocol=$port&discovery=$kademliaPort"
  private val storagePath       = conf.server.dataDir.resolve("rspace")
  private val casperStoragePath = storagePath.resolve("casper")
  private val storageSize       = conf.server.mapSize
  private val storeType         = conf.server.storeType
  private val defaultTimeout    = FiniteDuration(conf.server.defaultTimeout.toLong, MILLISECONDS) // TODO remove

  case class Servers(
      grpcServerExternal: Server,
      grpcServerInternal: Server,
      httpServer: Http4sServer[IO]
  )

  def acquireServers(runtime: Runtime)(
      implicit
      log: Log[Task],
      nodeDiscovery: NodeDiscovery[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      nodeCoreMetrics: NodeMetrics[Task],
      jvmMetrics: JvmMetrics[Task],
      connectionsCell: ConnectionsCell[Task]
  ): Effect[Servers] = {
    implicit val s: Scheduler = scheduler
    for {
      grpcServerExternal <- GrpcServer
                             .acquireExternalServer[Effect](
                               conf.grpcServer.portExternal,
                               conf.server.maxMessageSize,
                               grpcScheduler
                             )
      grpcServerInternal <- GrpcServer
                             .acquireInternalServer(
                               conf.grpcServer.portInternal,
                               conf.server.maxMessageSize,
                               runtime,
                               grpcScheduler
                             )
                             .toEffect
      prometheusReporter = new NewPrometheusReporter()

      httpServer <- LiftIO[Task].liftIO {
                     val prometheusService     = NewPrometheusReporter.service(prometheusReporter)
                     implicit val contextShift = IO.contextShift(scheduler)
                     BlazeBuilder[IO]
                       .bindHttp(conf.server.httpPort, "0.0.0.0")
                       .mountService(prometheusService, "/metrics")
                       .mountService(VersionInfo.service, "/version")
                       .start
                   }.toEffect
      _ <- Task.delay {
            Kamon.addReporter(prometheusReporter)
            Kamon.addReporter(new JmxReporter())
            Kamon.addReporter(new ZipkinReporter())
          }.toEffect
    } yield Servers(grpcServerExternal, grpcServerInternal, httpServer)
  }

  def startServers(servers: Servers)(
      implicit
      log: Log[Task]
  ): Effect[Unit] =
    GrpcServer.start(servers.grpcServerExternal, servers.grpcServerInternal).toEffect

  def clearResources(servers: Servers, runtime: Runtime, casperRuntime: Runtime)(
      implicit
      transport: TransportLayer[Task],
      log: Log[Task],
      blockStore: BlockStore[Effect],
      rpConfAsk: RPConfAsk[Task]
  ): Unit =
    (for {
      _   <- log.info("Shutting down gRPC servers...")
      _   <- Task.delay(servers.grpcServerExternal.shutdown())
      _   <- Task.delay(servers.grpcServerInternal.shutdown())
      _   <- log.info("Shutting down transport layer, broadcasting DISCONNECT")
      loc <- rpConfAsk.reader(_.local)
      msg = ProtocolHelper.disconnect(loc)
      _   <- transport.shutdown(msg)
      _   <- log.info("Shutting down HTTP server....")
      _   <- Task.delay(Kamon.stopAllReporters())
      _   <- LiftIO[Task].liftIO(servers.httpServer.shutdown).attempt
      _   <- log.info("Shutting down interpreter runtime ...")
      _   <- Task.delay(runtime.close)
      _   <- log.info("Shutting down Casper runtime ...")
      _   <- Task.delay(casperRuntime.close)
      _   <- log.info("Bringing BlockStore down ...")
      _   <- blockStore.close().value
      _   <- log.info("Goodbye.")
    } yield ()).unsafeRunSync(scheduler)

  def startReportJvmMetrics(
      implicit metrics: Metrics[Task],
      jvmMetrics: JvmMetrics[Task]
  ): Task[Unit] =
    Task.delay {
      import scala.concurrent.duration._
      loopScheduler.scheduleAtFixedRate(3.seconds, 3.second)(
        JvmMetrics.report[Task].unsafeRunSync(scheduler)
      )
    }

  def addShutdownHook(servers: Servers, runtime: Runtime, casperRuntime: Runtime)(
      implicit transport: TransportLayer[Task],
      log: Log[Task],
      blockStore: BlockStore[Effect],
      rpConfAsk: RPConfAsk[Task]
  ): Task[Unit] =
    Task.delay(sys.addShutdownHook(clearResources(servers, runtime, casperRuntime)))

  private def exit0: Task[Unit] = Task.delay(System.exit(0))

  private def nodeProgram(runtime: Runtime, casperRuntime: Runtime)(
      implicit
      log: Log[Task],
      time: Time[Task],
      rpConfAsk: RPConfAsk[Task],
      metrics: Metrics[Task],
      transport: TransportLayer[Task],
      nodeDiscovery: NodeDiscovery[Task],
      rpConnectons: ConnectionsCell[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      packetHandler: PacketHandler[Effect],
      casperConstructor: MultiParentCasperRef[Effect],
      nodeCoreMetrics: NodeMetrics[Task],
      jvmMetrics: JvmMetrics[Task]
  ): Effect[Unit] = {

    val info: Effect[Unit] = for {
      _ <- Log[Effect].info(VersionInfo.get)
      _ <- if (conf.server.standalone) Log[Effect].info(s"Starting stand-alone node.")
          else Log[Effect].info(s"Starting node that will bootstrap from ${conf.server.bootstrap}")
    } yield ()

    val loop: Effect[Unit] = for {
      _ <- Connect.clearConnections[Effect]
      _ <- Connect.findAndConnect[Effect](Connect.connect[Effect])
      _ <- time.sleep(5.seconds).toEffect
    } yield ()

    val casperLoop: Effect[Unit] =
      for {
        _ <- casperConstructor.get map {
              case Some(casper) => casper.fetchDependencies
              case None         => ().pure[Effect]
            }
        _ <- time.sleep(30.seconds).toEffect
      } yield ()

    for {
      _       <- info
      servers <- acquireServers(runtime)
      _       <- addShutdownHook(servers, runtime, casperRuntime).toEffect
      _       <- startServers(servers)
      _       <- startReportJvmMetrics.toEffect
      _ <- TransportLayer[Effect].receive(
            pm => HandleMessages.handle[Effect](pm, defaultTimeout),
            blob => packetHandler.handlePacket(blob.sender, blob.packet).as(())
          )
      _ <- NodeDiscovery[Task].discover.executeOn(loopScheduler).start.toEffect
      _ <- Log[Effect].info(s"Listening for traffic on $address.")
      _ <- EitherT(Task.defer(loop.forever.value).executeOn(loopScheduler))
      _ <- EitherT(Task.defer(casperLoop.forever.value).executeOn(loopScheduler))
    } yield ()
  }

  /**
    * Handles unrecoverable errors in program. Those are errors that should not happen in properly
    * configured enviornment and they mean immediate termination of the program
    */
  private def handleUnrecoverableErrors(prog: Effect[Unit])(implicit log: Log[Task]): Effect[Unit] =
    EitherT[Task, CommError, Unit](
      prog.value
        .onErrorHandleWith {
          case th =>
            log.error("Caught unhandable error. Exiting. Stacktrace below.") *> Task.delay {
              th.printStackTrace();
            }
        } *> exit0.as(Right(()))
    )

  private val syncEffect = SyncInstances.syncEffect[CommError](commError => {
    new Exception(s"CommError: $commError")
  }, e => { UnknownCommError(e.getMessage) })

  /**
    * Main node entry. It will:
    * 1. set up configurations
    * 2. create instances of typeclasses
    * 3. run the node program.
    */
  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler
  val main: Effect[Unit] = for {
    // 1. set up configurations
    local          <- EitherT.fromEither[Task](PeerNode.fromAddress(address))
    defaultTimeout = conf.server.defaultTimeout.millis
    rpClearConnConf = ClearConnetionsConf(
      conf.server.maxNumOfConnections,
      numOfConnectionsPinged = 10
    ) // TODO read from conf
    // 2. create instances of typeclasses
    rpConfAsk            = effects.rpConfAsk(RPConf(local, defaultTimeout, rpClearConnConf))
    tcpConnections       <- effects.tcpConnections.toEffect
    rpConnections        <- effects.rpConnections.toEffect
    log                  = effects.log
    time                 = effects.time
    metrics              = diagnostics.metrics[Task]
    multiParentCasperRef <- MultiParentCasperRef.of[Effect]
    lab                  <- LastApprovedBlock.of[Task].toEffect
    labEff               = LastApprovedBlock.eitherTLastApprovedBlock[CommError, Task](Monad[Task], lab)
    transport = effects.tcpTransportLayer(
      host,
      port,
      conf.tls.certificate,
      conf.tls.key,
      conf.server.maxMessageSize
    )(grpcScheduler, tcpConnections, log)
    kademliaRPC = effects.kademliaRPC(local, kademliaPort, defaultTimeout)(
      grpcScheduler,
      metrics,
      log
    )
    initPeer = if (conf.server.standalone) None else Some(conf.server.bootstrap)
    nodeDiscovery <- effects
                      .nodeDiscovery(local, defaultTimeout)(initPeer)(
                        log,
                        time,
                        metrics,
                        kademliaRPC
                      )
                      .toEffect
    // TODO: This change is temporary until itegulov's BlockStore implementation is in
    blockMap <- Ref.of[Effect, Map[BlockHash, BlockMessage]](Map.empty[BlockHash, BlockMessage])
    blockStore = InMemBlockStore.create[Effect](
      syncEffect,
      blockMap,
      Metrics.eitherT(Monad[Task], metrics)
    )
    _              <- blockStore.clear() // TODO: Replace with a proper casper init when it's available
    oracle         = SafetyOracle.turanOracle[Effect](Monad[Effect])
    runtime        = Runtime.create(storagePath, storageSize, storeType)
    _              <- runtime.injectEmptyRegistryRoot[Effect]
    casperRuntime  = Runtime.create(casperStoragePath, storageSize, storeType)
    runtimeManager = RuntimeManager.fromRuntime(casperRuntime)(scheduler)
    casperPacketHandler <- CasperPacketHandler
                            .of[Effect](conf.casper, defaultTimeout, runtimeManager, _.value)(
                              labEff,
                              Metrics.eitherT(Monad[Task], metrics),
                              blockStore,
                              Cell.eitherTCell(Monad[Task], rpConnections),
                              NodeDiscovery.eitherTNodeDiscovery(Monad[Task], nodeDiscovery),
                              TransportLayer.eitherTTransportLayer(Monad[Task], log, transport),
                              ErrorHandler[Effect],
                              eiterTrpConfAsk(rpConfAsk),
                              oracle,
                              Capture[Effect],
                              Sync[Effect],
                              Time.eitherTTime(Monad[Task], time),
                              Log.eitherTLog(Monad[Task], log),
                              multiParentCasperRef,
                              scheduler
                            )
    packetHandler = PacketHandler.pf[Effect](casperPacketHandler.handle)(
      Applicative[Effect],
      Log.eitherTLog(Monad[Task], log),
      ErrorHandler[Effect]
    )
    nodeCoreMetrics = diagnostics.nodeCoreMetrics[Task]
    jvmMetrics      = diagnostics.jvmMetrics[Task]
    // 3. run the node program.
    program = nodeProgram(runtime, casperRuntime)(
      log,
      time,
      rpConfAsk,
      metrics,
      transport,
      nodeDiscovery,
      rpConnections,
      blockStore,
      oracle,
      packetHandler,
      multiParentCasperRef,
      nodeCoreMetrics,
      jvmMetrics
    )
    _ <- handleUnrecoverableErrors(program)(log)
  } yield ()

}

object NodeRuntime {
  def apply(conf: Configuration, host: String, scheduler: Scheduler): Effect[NodeRuntime] =
    new InitNodeRuntime(conf, host, scheduler).init
}
