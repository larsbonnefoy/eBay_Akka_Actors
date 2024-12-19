//Auction uses Event Sourcing + Managed Queue
package eBayMicroServ
import java.util.UUID
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import scala.collection.immutable.TreeSet
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect

//Bid will probably need to be exposed so that a Bidder can place a Bid
//Should probably go into the Bidder Class
case class BidderRef(id: UUID)
case class Bid(bidder: BidderRef, amount: Int)

trait AuctionMessage

sealed trait AuctionCommand extends AuctionMessage
case class InitAuction(item: AuctionableItem, replyTo: ActorRef[AuctionEvent]) extends AuctionCommand //-> Dont need this message as the auction is created when Actor is initalized
case class RemoveAuction() extends AuctionCommand
case class PlaceBid(bid: Bid) extends AuctionCommand

sealed trait AuctionEvent extends AuctionMessage
case class AuctionCreated(item: AuctionableItem, auctionRef: AuctionRef) extends AuctionEvent
case class AuctionDeleted(acc: Auction) extends AuctionEvent
case class NewBidPlaced(bid: Bid) extends AuctionEvent
case class AckAuction(msg: String) extends AuctionEvent

implicit private val bidOrdering: Ordering[Bid]= Ordering.by(-_.amount)

case class AuctionRef(id: UUID)

//Default Auction object, used to impl state
object Auction {
  def default: Auction = Auction(
    item = null.asInstanceOf[AuctionableItem],
    auctionRef = null.asInstanceOf[AuctionRef],
    bids = TreeSet.empty[Bid]
  )
}

case class Auction(item: AuctionableItem, auctionRef: AuctionRef, bids: TreeSet[Bid]):

  private def currPrice : Int = bids.headOption match { 
    case Some(currentMaxBid) => currentMaxBid.amount
    case None => item.startingPrice
  }

  def placeBid(bid: Bid) = 
    require(bid.amount > currPrice, s"Bid is too low. Proposed price: ${bid.amount}, Current price: ${currPrice}")
    this.copy(bids = bids + bid)

  def initAuction(item: AuctionableItem, auctionRef: AuctionRef) = 
    require(item.startingPrice > 0, "Price cannot be negative")
    this.copy(item=item, auctionRef=auctionRef)

  def applyEvent(event: AuctionEvent) = 
    event match {
      case NewBidPlaced(bid) => placeBid(bid)
      case AuctionCreated(item, ref) => initAuction(item, ref)
      case AuctionDeleted(_) => ???
      case AckAuction(_) => ???  
    }

object PersistentAuctionManager:
  def apply(): Behavior[AuctionCommand] =
    Behaviors.setup { context =>

      val auctionUUID = mkUUID()

      EventSourcedBehavior[AuctionCommand, AuctionEvent, Auction] (
        persistenceId = PersistenceId.ofUniqueId(auctionUUID.toString()),
        emptyState = Auction.default, 
        commandHandler = {(_, command) =>
          command match {
            case PlaceBid(bid) => ???
            case RemoveAuction() => ???
            case InitAuction(item, replyTo) => {
              Effect.persist(AuctionCreated(item, AuctionRef(auctionUUID))).thenReply(replyTo)(_ => AckAuction("Created Auction"))
            }
          }
        }, 
        eventHandler = {(state, event) =>
          val updatedResult = state.applyEvent(event)
          context.log.info("Current state of the data: {}", updatedResult)
          updatedResult
        })
      }

  /*

      //TODO: Will require access to Bank and Ebay -> Receptionist

      val newAuc = Auction(owner, startingPrice, item, AuctionRef(mkUUID()))
      AuctionCreated(newAuc)

      def active(auction: Auction): Behavior[AuctionManagerInput] = {
        Behaviors.receive { (context, message) => 
          message match {
            case AuctionManagerCommand(cmd, id, replyTo) => 
              cmd match {
                case DeleteAuction() => context.log.error("Delete Current Auction"); Behaviors.same
              }
          }
        }

      }
      active(newAuc)
    } */

//class AuctionManager(context)
