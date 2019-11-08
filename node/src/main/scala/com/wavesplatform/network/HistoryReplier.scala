package com.wavesplatform.network

import com.wavesplatform.history.History
import com.wavesplatform.network.HistoryReplier._
import com.wavesplatform.settings.SynchronizationSettings
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Sharable
class HistoryReplier(score: => BigInt, history: History, settings: SynchronizationSettings)(implicit ec: ExecutionContext)
    extends ChannelInboundHandlerAdapter
    with ScorexLogging {

  private def respondWith(ctx: ChannelHandlerContext, value: Future[Message]): Unit =
    value.onComplete {
      case Failure(e) => log.debug(s"${id(ctx)} Error processing request", e)
      case Success(value) =>
        if (ctx.channel().isOpen) {
          ctx.writeAndFlush(value)
        } else {
          log.trace(s"${id(ctx)} Channel is closed")
        }
    }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case GetSignatures(otherSigs) =>
      respondWith(ctx, Future(Signatures(history.blockIdsAfter(otherSigs, settings.maxRollback))))

    case GetBlock(sig) =>
      respondWith(
        ctx,
        Future(history.loadBlockBytes(sig))
          .map(bb => RawBytes(BlockSpec.messageCode, bb.getOrElse(throw new NoSuchElementException(s"Error loading block $sig"))))
      )

    case MicroBlockRequest(totalResBlockSig) =>
      respondWith(
        ctx,
        Future(
          RawBytes(
            MicroBlockResponseSpec.messageCode,
            history.loadMicroBlockBytes(totalResBlockSig).getOrElse(throw new NoSuchElementException(s"Error loading microblock $totalResBlockSig"))
          )
        )
      )

    case _: Handshake =>
      respondWith(ctx, Future(LocalScoreChanged(score)))

    case _ => super.channelRead(ctx, msg)
  }

  def cacheSizes: CacheSizes = CacheSizes(0, 0)
}

object HistoryReplier {
  case class CacheSizes(blocks: Long, microBlocks: Long)
}
