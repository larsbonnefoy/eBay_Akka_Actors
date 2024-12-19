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
import akka.actor.typed.receptionist.Receptionist

//Bid will probably need to be exposed so that a Bidder can place a Bid
//Should probably go into the Bidder Class
case class Bid(bidder: BidderRef, amount: Int)

trait AuctionMessage

sealed trait AuctionCommand extends AuctionMessage
case class InitAuction(item: AuctionableItem, replyToAuc: ActorRef[AuctionEvent], replyToEbay: ActorRef[ebayEvent]) extends AuctionCommand //-> Dont need this message as the auction is created when Actor is initalized
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
    //require(bid.amount > currPrice, s"Bid is too low. Proposed price: ${bid.amount}, Current price: ${currPrice}")
    this.copy(bids = bids + bid)

  def initAuction(item: AuctionableItem, auctionRef: AuctionRef) = 
    this.copy(item=item, auctionRef=auctionRef)

  def applyEvent(event: AuctionEvent) = 
    event match {
      case NewBidPlaced(bid) => placeBid(bid)
      case AuctionCreated(item, ref) => initAuction(item, ref)
      case AuctionDeleted(_) => ???
      case AckAuction(_) => ???  
    }

object PersistentAuctionManager:

  private final case class WrappedBankResponse(resp: BankGatewayResponse) extends AuctionCommand
  private final case class ReceptionistEbay(bankRef: Option[ActorRef[ebayCommand]]) extends AuctionCommand

  def apply(): Behavior[AuctionCommand] =
    Behaviors.setup { context =>

      val auctionUUID = mkUUID()

      //Different actor references are kept within the Manager 
      //and not the state
      var ebayRef: Option[ActorRef[ebayCommand]] = None
      var ownerRefAuction: Option[ActorRef[AuctionEvent]] = None
      var ownerRefEbay: Option[ActorRef[ebayEvent]] = None

      val ebayReceptionistMapper: ActorRef[Receptionist.Listing] = context.messageAdapter { 
        case PersistentEbayManager.Key.Listing(set) => ReceptionistEbay(set.headOption)
      }
      context.log.info(s"Created Auction Manager")

      EventSourcedBehavior[AuctionCommand, AuctionEvent, Auction] (
        persistenceId = PersistenceId.ofUniqueId(auctionUUID.toString()),
        emptyState = Auction.default, 
        commandHandler = {(state, command) =>
          command match {
            case PlaceBid(bid) => ???
            case RemoveAuction() => ???
            case InitAuction(item, replyToAuc, replyToEbay) => {
              ownerRefAuction = Some(replyToAuc)
              ownerRefEbay = Some(replyToEbay)
              try {
                require(item.startingPrice > 0, "Price cannot be negative")
                //Require Ebay ref to send created auction
                context.system.receptionist ! Receptionist.Find(PersistentEbayManager.Key, ebayReceptionistMapper)
                Effect.persist(AuctionCreated(item, AuctionRef(auctionUUID)))
                  .thenReply(replyToAuc)(_ => AckAuction("Auction Valid"))
              }
              catch {
                case e: Exception => Effect.none.thenReply(replyToAuc)(_ => AckAuction(e.getMessage()))
              }
            }
            case WrappedBankResponse(_) => ???

            case ReceptionistEbay(optionRef) => {
              optionRef match {
                case Some(ref) => {
                  ebayRef = optionRef
                  context.log.info("Sent Registration Request")
                  ref ! RegisterAuction(AuctionListing(state.item.owner.sellerId, state.auctionRef, context.self), ownerRefEbay.get)
                }
                case None => context.log.error("eBay not registered")
              }
              Effect.none
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
