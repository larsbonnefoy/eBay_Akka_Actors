//Auction uses Event Sourcing + Managed Queue
import scala.collection.mutable.PriorityQueue
import java.util.UUID
import akka.actor.typed.ActorRef

enum AuctionableItem {
  case Item1, Item2
}


//Bid will probably need to be exposed so that a Bidder can place a Bid
//Should probably go into the Bidder Class
case class BidderRef(id: UUID)
case class Bid(bidder: BidderRef, amount: Int)

trait AuctionMessage

sealed trait AuctionCommand extends AuctionMessage
// case class CreateAuction() extends AuctionMessage
case class PlaceBid(bid: Bid) extends AuctionCommand

sealed trait  AuctionEvent extends AuctionMessage
case class NewBidPlaced(bid: Bid) extends AuctionEvent

trait AuctionManagerInput 
case class AuctionManagerCommand(cmd: AuctionCommand, id: UUID, replyTo: ActorRef[AuctionManagerResponse]) extends AuctionManagerInput
// case class AuctionManagerQuery(cmd: AuctionCommand, replyTo: ActorRef[AuctionManagerResponse]) extends AuctionManagerInput

trait AuctionManagerResponse
case class AuctionManagerEvent(id: UUID, event: AuctionEvent) extends AuctionManagerResponse
case class AuctionManagerRejection(id: UUID, reason: String) extends AuctionManagerResponse


// TODO: Owner of Auction should be identifiable with some unique ID -> SellerRef
class Auction(owner: String, startingPrice: Int, item: AuctionableItem):

  // Reverse ordering so that Max Bid is in Front
  implicit val bidOrdering: Ordering[Bid]= Ordering.by(-_.amount)

  //Need some sorted DS to hold Bids
  //Makes it possible to retreive highest bid easily, could change impl to something
  //else if withdraw occures more often
  val pq: PriorityQueue[Bid] = PriorityQueue()

  /**
    *  max is Some(currentMaxBid) or None if no bid has been placed
    */
  val max : Option[Bid] = if (pq.isEmpty) None else Some(pq.head)

  def placeBid(bid: Bid) = 
    require(max match { 
      case Some(currentMaxBid) => bid.amount > currentMaxBid.amount
      case None => bid.amount > startingPrice
    })
    pq.enqueue(bid)

  def applyEvent(event: AuctionEvent) = event match {
    case NewBidPlaced(bid) => placeBid(bid)
  }

// object PersistentAuctionManager:
//   def apply()

//class AuctionManager(context)
