package eBayMicroServ

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import _root_.eBayMicroServ.StatusCode.OK
import scala.concurrent.Future
import scala.concurrent.Promise

/**
  * Add a manager so that the BankGateway does not need 
  * to keep track of communication, we Manager through context.ask
  * One big flaw is that Seller and Buyer expect BankGateway Response Protocol
  * => We need to be transparent to Sellers and Buyers and copy most of the protocol over.
  * As both buyers and seller respond via the BankGateway protocol there is not direct way of 
  * telling them appart from the protocol. We thus identify them via a tmp correlation Id.
  */
object TransactionManager {
  sealed trait Command
  case class StartTransaction( seller: ActorRef[BankGateway.Response], bidder: ActorRef[BankGateway.Response], replyTo: ActorRef[Response]) extends Command

  sealed trait Response
  case class FinishedTransaction(code: StatusCode, msg: String) extends Response

  private case class SellerAck(code: StatusCode) extends Command
  private case class BidderAck(code: StatusCode) extends Command
  private case class FailedTransaction(reason: String) extends Command
  private case object SuccessTransaction extends Command

  def apply(): Behavior[Command] = 
    import scala.concurrent.ExecutionContext.Implicits.global
    Behaviors.setup { context => 
      implicit val timeout: Timeout = 5.seconds

      var sellerResponse: Option[SellerAck] = None
      var bidderResponse: Option[BidderAck] = None

      def checkCompletion(): Unit = {
        (sellerResponse, bidderResponse) match {
          case (Some(SellerAck(OK)), Some(BidderAck(OK))) =>
            context.self ! SuccessTransaction 
          case (Some(SellerAck(StatusCode.Failed)), _) =>
            context.self ! FailedTransaction("Seller Rejected Transaction")
          case (_, Some(BidderAck(StatusCode.Failed))) =>
            context.self ! FailedTransaction("Bidder Rejected Transaction")
          case _ => // Still waiting for responses
        }
      }

      var maybeBankRef: Option[ActorRef[Response]] = None

      Behaviors.receiveMessage {
        case StartTransaction(seller, bidder, replyToBank) => {

          maybeBankRef = Some(replyToBank)

          context.ask(seller, (replyTo: ActorRef[BankGateway.Command]) => BankGateway.TransactionAckQuery(replyTo)) {
            case Success(BankGateway.TranscationAckResponse(code)) => SellerAck(code)
            case msg @ Success(_) => FailedTransaction(s"Got unexpected message: ${msg}")
            case Failure(exception) => FailedTransaction(exception.getMessage())
          }

          context.ask(bidder, (replyTo: ActorRef[BankGateway.Command]) => BankGateway.TransactionAckQuery(replyTo)) {
            case Success(BankGateway.TranscationAckResponse(code)) => BidderAck(code)
            case msg @ Success(_) => FailedTransaction(s"Got unexpected message: ${msg}")
            case Failure(exception) => FailedTransaction(exception.getMessage())
          }

          Behaviors.same
        }

        case SuccessTransaction => {
          maybeBankRef match {
            case Some(ref) => ref ! FinishedTransaction(StatusCode.OK, "Transaction completed successfully")
            case None => context.log.error("Could no retreive answer ref")
          }
          Behaviors.stopped
        }

        case FailedTransaction(msg) => {
          context.log.error(msg)
          maybeBankRef match {
            case Some(ref) => ref ! FinishedTransaction(StatusCode.Failed, msg)
            case None => context.log.error("Could no retreive answer ref")
          }
          Behaviors.stopped
        }

        case ack @ BidderAck(code) => {
          bidderResponse = Some(ack)
          checkCompletion()
          Behaviors.same
        } 

        case ack @ SellerAck(code) => {
          sellerResponse = Some(ack)
          checkCompletion()
          Behaviors.same
        }
      }
    }
}
