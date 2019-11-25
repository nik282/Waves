package com.wavesplatform.api.grpc
import com.wavesplatform.api.common.CommonTransactionsApi
import com.wavesplatform.protobuf.transaction._
import com.wavesplatform.transaction.Authorized
import io.grpc.stub.StreamObserver
import io.grpc.{Status, StatusRuntimeException}
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

class TransactionsApiGrpcImpl(commonApi: CommonTransactionsApi)(implicit sc: Scheduler) extends TransactionsApiGrpc.TransactionsApi {

  override def getTransactions(request: TransactionsRequest, responseObserver: StreamObserver[TransactionResponse]): Unit =
    responseObserver.interceptErrors {
      val stream = request.recipient match {
        case Some(subject) =>
          commonApi.transactionsByAddress(
            subject.toAddressOrAlias,
            Option(request.sender).filter(!_.isEmpty).map(_.toAddress),
            Set.empty,
            None
          )
        case None =>
          Observable.fromIterable(request.transactionIds.flatMap(id => commonApi.transactionById(id.toByteStr)))
      }

      responseObserver.completeWith(stream.map {
        case (h, tx) => TransactionResponse(tx.id().toPBByteString, h, Some(tx.toPB))
      })
    }

  override def getUnconfirmed(request: TransactionsRequest, responseObserver: StreamObserver[TransactionResponse]): Unit =
    responseObserver.interceptErrors {
      val unconfirmedTransactions = if (!request.sender.isEmpty) {
        val senderAddress = request.sender.toAddress
        commonApi.unconfirmedTransactions.collect {
          case a: Authorized if a.sender.toAddress == senderAddress => a
        }
      } else {
        request.transactionIds.flatMap(id => commonApi.unconfirmedTransactionById(id.toByteStr))
      }

      responseObserver.completeWith(
        Observable.fromIterable(unconfirmedTransactions.map(t => TransactionResponse(t.id().toPBByteString, transaction = Some(t.toPB))))
      )
    }

  override def getStateChanges(request: TransactionsRequest, responseObserver: StreamObserver[InvokeScriptResult]): Unit =
    responseObserver.interceptErrors {
      import com.wavesplatform.state.{InvokeScriptResult => VISR}

      val result = Observable(request.transactionIds: _*)
        .flatMap(txId => Observable.fromIterable(commonApi.invokeScriptResultById(txId.toByteStr)))
        .map { case (_, _, isr) => VISR.toPB(isr) }

      responseObserver.completeWith(result)
    }

  override def getStatuses(request: TransactionsByIdRequest, responseObserver: StreamObserver[TransactionStatus]): Unit =
    responseObserver.interceptErrors {
      val result = Observable(request.transactionIds: _*).map { txId =>
        commonApi
          .unconfirmedTransactionById(txId)
          .map(_ => TransactionStatus(txId, TransactionStatus.Status.UNCONFIRMED))
          .orElse {
            commonApi.transactionById(txId).map { case (h, _) => TransactionStatus(txId, TransactionStatus.Status.CONFIRMED, h) }
          }
          .getOrElse(TransactionStatus(txId, TransactionStatus.Status.NOT_EXISTS))
      }
      responseObserver.completeWith(result)
    }

  override def sign(request: SignRequest): Future[PBSignedTransaction] = Future {
    throw new StatusRuntimeException(Status.UNIMPLEMENTED)
  }

  override def broadcast(tx: PBSignedTransaction): Future[PBSignedTransaction] = Future {
    commonApi
      .broadcastTransaction(tx.toVanilla)
      .resultE
      .map(_ => tx)
      .explicitGetErr()
  }
}
