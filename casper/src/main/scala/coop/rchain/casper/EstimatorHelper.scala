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
        b1MetaDataOpt        <- dag.lookup(b1.blockHash)
        b2MetaDataOpt        <- dag.lookup(b2.blockHash)
        blockMetaDataSeq     = Vector(b1MetaDataOpt.get, b2MetaDataOpt.get)
        uncommonAncestorsMap <- DagOperations.uncommonAncestors[F](blockMetaDataSeq, dag)
        (b1AncestorsMap, b2AncestorsMap) = uncommonAncestorsMap.partition {
          case (_, bitSet) => bitSet == BitSet(0)
        }
        b1AncestorsMeta           = b1AncestorsMap.keys
        b2AncestorsMeta           = b2AncestorsMap.keys
        b1EventsRes               <- blockEvents[F](b1AncestorsMeta.toList)
        (b1Events, allB1Channels) = b1EventsRes
        b2EventsRes               <- blockEvents[F](b2AncestorsMeta.toList)
        (b2Events, allB2Channels) = b2EventsRes
        conflictsBecauseOfJoins = joinedChannels(b1Events)
          .intersect(allB2Channels)
          .nonEmpty || joinedChannels(b2Events).intersect(allB1Channels).nonEmpty
        b1Ops = operationsPerChannel(b1Events)
        b2Ops = operationsPerChannel(b2Events)
        conflictingChannels = b1Ops
          .map {
            case (k, v) =>
              (k, channelConflicts(v, b2Ops.get(k).getOrElse(Set.empty)))
          }
          .filter { case (_, v) => v }
          .keys
        conflicts = conflictsBecauseOfJoins || conflictingChannels.nonEmpty
        _ <- if (conflicts) {
              Log[F].info(
                s"Block ${PrettyPrinter.buildString(b1.blockHash)} and ${PrettyPrinter
                  .buildString(b2.blockHash)} conflicts."
              )
            } else {
              Log[F].info(
                ""
                /*
                  s"Block ${PrettyPrinter
                    .buildString(b1.blockHash)}'s channels ${b1AncestorChannels.map(PrettyPrinter.buildString).mkString(",")} and block ${PrettyPrinter
                    .buildString(b2.blockHash)}'s channels ${b2AncestorChannels.map(PrettyPrinter.buildString).mkString(",")} don't intersect."
               */
              )
            }
      } yield conflicts
    }

  private[this] def isVolatile(comm: COMM, consumes: Set[Consume], produces: Set[Produce]) =
    !comm.consume.persistent && consumes.contains(comm.consume) && comm.produces.forall(
      produce => !produce.persistent && produces.contains(produce)
    )

  type BlockEvents = (Set[Produce], Set[Consume], Set[COMM])

  private[this] def blockEvents[F[_]: Monad: BlockStore](
      blockAncestorsMeta: List[BlockMetadata]
  ): F[(BlockEvents, Set[Blake2b256Hash])] =
    for {
      maybeAncestors <- blockAncestorsMeta.traverse(
                         blockAncestorMeta => BlockStore[F].get(blockAncestorMeta.blockHash)
                       )
      ancestors = maybeAncestors.flatten
      // TODO: convert directly to TuplespaceEvents instead of going via toRspaceEvent
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
      allChannels = freeProduceEvents.map(_.channelsHash).toSet ++ freeConsumeEvents
        .flatMap(_.channelsHashes)
        .toSet ++ nonVolatileCommEvents.flatMap { comm =>
        comm.consume.channelsHashes ++ comm.produces.map(_.channelsHash)
      }.toSet
    } yield ((freeProduceEvents, freeConsumeEvents, nonVolatileCommEvents), allChannels)

  private[this] def channelConflicts(
      b1Events: Set[TuplespaceEvent],
      b2Events: Set[TuplespaceEvent]
  ): Boolean =
    (for {
      b1  <- b1Events
      b2  <- b2Events
      res = b1.conflicts(b2)
      // TODO: fail fast
    } yield (res)).contains(true)

  private[this] def joinedChannels(b: BlockEvents): Set[Blake2b256Hash] = b match {
    case (_, consumes, comms) =>
      val consumesJoins = consumes.collect {
        case Consume(channelsHashes, _, _, _) if channelsHashes.size > 1 => channelsHashes
      }
      val commsJoins = comms.collect {
        case COMM(Consume(channelsHashes, _, _, _), _, _) if channelsHashes.size > 1 =>
          channelsHashes
      }
      (consumesJoins ++ commsJoins).flatten
  }

  private[this] def operationsPerChannel(
      b: BlockEvents
  ): Map[Blake2b256Hash, Set[TuplespaceEvent]] =
    b match {
      case (produces, consumes, comms) =>
        val produceEvents = produces
          .groupBy(_.channelsHash)
          .mapValues[Set[TuplespaceEvent]](
            ops =>
              ops.map(
                p =>
                  NoMatch(
                    if (p.persistent) NonLinearProduce(p.hash)
                    else LinearProduce(p.hash)
                  )
              )
          )
        val consumeEvents = consumes
          .collect {
            case Consume(singleChannelHash :: Nil, hash, persistent, _) =>
              singleChannelHash -> NoMatch(
                if (persistent) NonLinearConsume(hash)
                else LinearConsume(hash)
              )
          }
          .groupBy(_._1)
          .mapValues[Set[TuplespaceEvent]](_.map(_._2))

        val commEvents = comms
          .collect {
            case COMM(consume, produce :: Nil, _) => {
              val cop =
                if (consume.persistent) NonLinearConsume(consume.hash)
                else LinearConsume(consume.hash)
              val pop =
                if (produce.persistent) NonLinearProduce(produce.hash)
                else LinearProduce(produce.hash)
              produce.channelsHash -> Match(
                cop,
                pop,
                if (produces.contains(produce)) cop
                else pop
              )
            }
          }
          .groupBy(_._1)
          .mapValues[Set[TuplespaceEvent]](_.map(_._2))

        produceEvents.combine(consumeEvents).combine(commEvents)
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
