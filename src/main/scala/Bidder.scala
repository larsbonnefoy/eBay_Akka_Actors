package eBayMicroServ
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AbstractBehavior
import java.util.UUID
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.Actor

/*****Bidder Protocol********/
final case class BidderRef(id: UUID)
final case class Bidder(user: User, BidderId: BidderRef)
final case class DisplayableAuction(item: String, price: Int, replyTo: ActorRef[AuctionCommand])

sealed trait BidderMessages
// case class MakeBid() extends BidderAction
// case class WithdrawBid() extends BidderAction
sealed trait BidderAction extends BidderMessages
case class GetAuctions() extends BidderAction
case class AuctionsResult(auctions: Seq[DisplayableAuction]) extends BidderAction


object Bidder:
  private final case class WrappedAuth(resp: AuthUser) extends BidderAction

  def apply(user: User, ebayRef: ActorRef[ebayCommand]): Behavior[BidderAction] = {
    Behaviors.setup { context =>

      val authMapper: ActorRef[AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))

      val asker = context.spawnAnonymous(BankAsker())
      asker ! StartRegistration(user, authMapper)

      def active(bidder: Bidder): Behavior[BidderAction] = {
        Behaviors.receive { (context, message) =>
          message match {
            case WrappedAuth(auth) => {
              context.log.info(s"Got Account from Bank ${auth.user}") 
              active(bidder.copy(user = auth.user))
            }
            case GetAuctions() => 
              bidder.user.bank match {
                case Some(_) => ebayRef ! AvailableAuctions(context.self); active(bidder)
                case None => context.log.error("User has no Bank Account"); active(bidder)
              }
            case AuctionsResult(res) => context.log.info(s"Got results ${res}"); active(bidder)
          }
        }
      }
      active(Bidder(user, BidderRef(mkUUID())))
    }
  }
