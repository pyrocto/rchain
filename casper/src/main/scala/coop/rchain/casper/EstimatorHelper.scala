package coop.rchain.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockDagRepresentation, BlockStore}
import coop.rchain.casper.protocol.{Event => CasperEvent, _}
import coop.rchain.casper.util.{DagOperations, EventConverter, ProtoUtil}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.shared.Log
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.trace._

import scala.collection.BitSet

object EstimatorHelper {

  def chooseNonConflicting[F[_]: Monad: Log: BlockStore](
      blockHashes: Seq[BlockHash],
      dag: BlockDagRepresentation[F]
  ): F[Seq[BlockMessage]] = {
    def nonConflicting(b: BlockMessage): BlockMessage => F[Boolean] =
      conflicts[F](_, b, dag).map(b => !b)

    for {
      blocks <- blockHashes.toList.traverse(hash => ProtoUtil.unsafeGetBlock[F](hash))
      result <- blocks
                 .foldM(List.empty[BlockMessage]) {
                   case (acc, b) =>
                     Monad[F].ifM(acc.forallM(nonConflicting(b)))(
                       (b :: acc).pure[F],
                       acc.pure[F]
                     )
                 }
                 .map(_.reverse)
    } yield result
  }

  private[casper] def conflicts[F[_]: Monad: Log: BlockStore](
      b1: BlockMessage,
      b2: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Boolean] =
    dag.deriveOrdering(0L).flatMap { implicit ordering =>
      for {
        b1MetaDataOpt <- dag.lookup(b1.blockHash)
        b2MetaDataOpt <- dag.lookup(b2.blockHash)
        uncommonAncestorsMap <- DagOperations.uncommonAncestors[F](
                                 Vector(b1MetaDataOpt.get, b2MetaDataOpt.get),
                                 dag
                               )
        (b1AncestorsMap, b2AncestorsMap) = uncommonAncestorsMap.partition {
          case (_, bitSet) => bitSet == BitSet(0)
        }
        b1Events <- extractBlockEvents[F](b1AncestorsMap.keys.toList)
        b2Events <- extractBlockEvents[F](b2AncestorsMap.keys.toList)
        conflictsBecauseOfJoins = extractJoinedChannels(b1Events)
          .intersect(allChannels(b2Events))
          .nonEmpty || extractJoinedChannels(b2Events).intersect(allChannels(b1Events)).nonEmpty
        conflicts = conflictsBecauseOfJoins || containConflictingEvents(b1Events, b2Events)
        _ <- if (conflicts) {
              Log[F].info(
                s"Blocks ${PrettyPrinter.buildString(b1.blockHash)} and ${PrettyPrinter
                  .buildString(b2.blockHash)} conflict."
              )
            } else {
              Log[F].info(
                s"Blocks ${PrettyPrinter
                  .buildString(b1.blockHash)} and ${PrettyPrinter
                  .buildString(b2.blockHash)} don't conflict."
              )
            }
      } yield conflicts
    }

  private[this] def containConflictingEvents(
      b1Events: BlockEvents,
      b2Events: BlockEvents
  ): Boolean = {
    def channelConflicts(
        b1Events: Set[TuplespaceEvent],
        b2Events: Set[TuplespaceEvent]
    ): Boolean =
      (for {
        b1  <- b1Events
        b2  <- b2Events
        res = b1.conflicts(b2)
        // TODO: fail fast
      } yield (res)).contains(true)

    val b2Ops = tuplespaceEventsPerChannel(b2Events)
    tuplespaceEventsPerChannel(b1Events)
      .map {
        case (k, v) =>
          (k, channelConflicts(v, b2Ops.get(k).getOrElse(Set.empty)))
      }
      .filter { case (_, v) => v }
      .keys
      .nonEmpty
  }

  private[this] def isVolatile(comm: COMM, consumes: Set[Consume], produces: Set[Produce]) =
    !comm.consume.persistent && consumes.contains(comm.consume) && comm.produces.forall(
      produce => !produce.persistent && produces.contains(produce)
    )

  type BlockEvents = (Set[Produce], Set[Consume], Set[COMM])

  private[this] def allChannels(events: BlockEvents) = events match {
    case (freeProduceEvents, freeConsumeEvents, nonVolatileCommEvents) =>
      freeProduceEvents.map(_.channelsHash).toSet ++ freeConsumeEvents
        .flatMap(_.channelsHashes)
        .toSet ++ nonVolatileCommEvents.flatMap { comm =>
        comm.consume.channelsHashes ++ comm.produces.map(_.channelsHash)
      }.toSet
  }

  private[this] def extractBlockEvents[F[_]: Monad: BlockStore](
      blockAncestorsMeta: List[BlockMetadata]
  ): F[BlockEvents] =
    for {
      maybeAncestors <- blockAncestorsMeta.traverse(
                         blockAncestorMeta => BlockStore[F].get(blockAncestorMeta.blockHash)
                       )
      ancestors = maybeAncestors.flatten
      ancestorEvents = (ancestors.flatMap(_.getBody.deploys.flatMap(_.deployLog)) ++
        ancestors.flatMap(_.getBody.deploys.flatMap(_.paymentLog)))
        .map(EventConverter.toRspaceEvent)
        .toSet

      allProduceEvents = ancestorEvents.collect { case p: Produce => p }
      allConsumeEvents = ancestorEvents.collect { case c: Consume => c }
      allCommEvents    = ancestorEvents.collect { case c: COMM    => c }
      nonVolatileCommEvents = allCommEvents
        .filterNot(isVolatile(_, allConsumeEvents, allProduceEvents))
        .toSet
      producesInCommEvents = allCommEvents.flatMap(_.produces)
      consumesInCommEvents = allCommEvents.map(_.consume)
      freeProduceEvents    = allProduceEvents.filterNot(producesInCommEvents.contains(_))
      freeConsumeEvents    = allConsumeEvents.filterNot(consumesInCommEvents.contains(_))
    } yield (freeProduceEvents, freeConsumeEvents, nonVolatileCommEvents)

  private[this] def extractJoinedChannels(b: BlockEvents): Set[Blake2b256Hash] = {
    def joinedChannels(consumes: Set[Consume]) =
      consumes.withFilter(Consume.hasJoins).flatMap(_.channelsHashes)
    b match {
      case (_, consumes, comms) =>
        joinedChannels(consumes) ++ joinedChannels(comms.map(_.consume))
    }
  }

  private[this] def tuplespaceEventsPerChannel(
      b: BlockEvents
  ): Map[Blake2b256Hash, Set[TuplespaceEvent]] =
    b match {
      case (produces, consumes, comms) =>
        val produceEvents = produces
          .map(TuplespaceEvent.from(_))

        val consumeEvents = consumes
          .flatMap(TuplespaceEvent.from(_))

        val commEvents = comms
          .flatMap(TuplespaceEvent.from(_, produces))

        (produceEvents
          .combine(consumeEvents)
          .combine(commEvents))
          .groupBy(_._1)
          .mapValues[Set[TuplespaceEvent]](_.map(_._2))
    }

  sealed trait TuplespaceOperation extends Product with Serializable {
    def hash: Blake2b256Hash
  }
  sealed trait ProduceOperation                           extends TuplespaceOperation
  sealed trait ConsumeOperation                           extends TuplespaceOperation
  final case class LinearProduce(hash: Blake2b256Hash)    extends ProduceOperation
  final case class NonLinearProduce(hash: Blake2b256Hash) extends ProduceOperation
  final case class LinearConsume(hash: Blake2b256Hash)    extends ConsumeOperation
  final case class NonLinearConsume(hash: Blake2b256Hash) extends ConsumeOperation

  sealed trait TuplespaceEvent
  final case class Match(
      consume: ConsumeOperation,
      produce: ProduceOperation,
      incomingEvent: TuplespaceOperation
  ) extends TuplespaceEvent
  final case class NoMatch(op: TuplespaceOperation) extends TuplespaceEvent

  object TuplespaceEvent {

    implicit private[this] def liftProduce(produce: Produce): ProduceOperation =
      if (produce.persistent) NonLinearProduce(produce.hash)
      else LinearProduce(produce.hash)

    implicit private[this] def liftConsume(consume: Consume): ConsumeOperation =
      if (consume.persistent) NonLinearConsume(consume.hash)
      else LinearConsume(consume.hash)

    def from(produce: Produce): (Blake2b256Hash, TuplespaceEvent) = produce.channelsHash -> NoMatch(
      produce
    )

    def from(consume: Consume): Option[(Blake2b256Hash, TuplespaceEvent)] = consume match {
      case Consume(singleChannelHash :: Nil, _, _, _) =>
        Some(
          singleChannelHash -> NoMatch(
            consume
          )
        )
      case _ => None
    }

    def from(comm: COMM, produces: Set[Produce]): Option[(Blake2b256Hash, TuplespaceEvent)] =
      comm match {
        case COMM(consume, produce :: Nil, _) => {
          Some(
            produce.channelsHash -> Match(
              consume,
              produce,
              if (produces.contains(produce)) consume
              else produce
            )
          )
        }
        case _ => None
      }
  }

  // define ordering of events to reduce duplication in the pattern match
  // e.g. no match always comes before a match, produce before consume etc.
  // TODO: use scoring to avoid pattern matching hell
  implicit private[this] val tuplespaceEventOrdering = new Ordering[TuplespaceEvent] {
    def compare(l: TuplespaceEvent, r: TuplespaceEvent) = l match {
      case NoMatch(NonLinearProduce(_) | NonLinearConsume(_)) =>
        r match {
          case NoMatch(LinearProduce(_) | LinearConsume(_)) => 1
          case NoMatch(_)                                   => 0
          case Match(_, _, _)                               => -1
        }
      case NoMatch(LinearProduce(_) | LinearConsume(_)) =>
        r match {
          case NoMatch(NonLinearProduce(_) | NonLinearConsume(_)) => -1
          case NoMatch(_)                                         => 0
          case Match(_, _, _)                                     => -1
        }
      case Match(_, _, _) =>
        r match {
          case NoMatch(_)     => 1
          case Match(_, _, _) => 0
        }
    }
  }

  private[this] val Conflicts = true
  private[this] val Merges    = false

  implicit class TuplespaceEventOps(val ev: TuplespaceEvent) extends AnyVal {

    private[casper] def conflicts(other: TuplespaceEvent): Boolean = {
      // order ev and other
      val e1 = Ordering[TuplespaceEvent].min(ev, other)
      val e2 = if (ev == e1) other else ev
      e1 match {
        case NoMatch(_: LinearProduce) => // !X
          e2 match {
            case NoMatch(_: LinearProduce)                                      => Merges // !X
            case NoMatch(_: ConsumeOperation)                                   => Conflicts // 4X
            case Match(LinearConsume(_), LinearProduce(_), _ @LinearProduce(_)) => Merges // !4
            case Match(LinearConsume(_), LinearProduce(_), _ @LinearConsume(_)) => Merges // 4!
            case _                                                              => Conflicts
          }

        case NoMatch(_: LinearConsume) => // 4X
          e2 match {
            case NoMatch(_: LinearProduce)                                      => Conflicts // !X
            case NoMatch(_: ConsumeOperation)                                   => Merges // 4X
            case Match(LinearConsume(_), LinearProduce(_), _ @LinearProduce(_)) => Merges // !4
            case Match(LinearConsume(_), LinearProduce(_), _ @LinearConsume(_)) => Merges // 4!
            case _                                                              => Conflicts
          }
        case _ => Conflicts
      }
    }
  }
}
