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
import akka.actor.typed.pubsub
import akka.actor.Actor
import scala.util.Success
import scala.util.Try
import scala.util.Failure
import akka.util.Timeout
import _root_.eBayMicroServ.Seller.Info
import akka.actor.typed.pubsub.PubSub
import akka.actor.typed.pubsub.Topic

object Auction:
  case class Id(id: UUID)

  

  //With PubSub, Commands are used to subscribe (Placing a Bid) and unsubscribe(Removing Bid)
  //Events are used as Published Messages
  trait Message

  
  sealed trait Command extends Message
  case class Remove(seller: Seller.Info, replyTo: ActorRef[Seller.Command]) extends Command
  case class PlaceBid(bid: Bid, replyTo: ActorRef[Auction.Event]) extends Command
  case class CancelBid(bidder: Bidder.Info, replyTo: ActorRef[Auction.Event]) extends Command
  case class GetMaxBid(replyTo: ActorRef[AuctionList.ResultFragment]) extends Command

  sealed trait Event extends Message
  private final case class AuctionCreated(item: AuctionableItem, auctionId: Id) extends Event
  private final case class BidPlaced(bid: Bid, replyTo: ActorRef[Bidder.Command]) extends Event
  final case class RemovedBid(bidder: Bidder.Info) extends Event
  // case class AuctionDeleted(acc: Auction) extends AuctionEvent
  // case class NewBidPlaced(bid: Bid) extends AuctionEvent
  // case class AckAuction(msg: String) extends AuctionEvent

  /**Internal Protocol**/
  implicit private val bidOrdering: Ordering[BidState]= Ordering.by(-_.bid.amount)
  private case class Init(item: AuctionableItem, replyTo: ActorRef[Seller.Command]) extends Command //-> Dont need this message as the auction is created when Actor is initalized
  private final case class BankResponse(resp: BankGatewayResponse) extends Command
  private final case class ReceptionistEbay(bankRef: Option[ActorRef[eBay.Command]], replyTo: ActorRef[Seller.Command]) extends Command

  private final case class BidState(bid: Bid, replyTo: ActorRef[Bidder.Command])

  private case class State(item: AuctionableItem, auctionId: Id, bids: TreeSet[BidState]):

    private def currPrice : Int = bids.headOption match { 
      case Some(maxState) => maxState.bid.amount
      case None => item.startingPrice
    }

    def placeBid(bid: BidState) = 
      this.copy(bids = bids + bid)

    def removeBid(bidder: Bidder.Info) = 
      val toRemove =  bids.find(state => state.bid.bidder.id == bidder.id)
      toRemove match {
        case Some(bid) =>  this.copy(bids = bids - bid)
        case None => println(s"Error in Auction.State ${bidder} has not placed any bet on ${this}"); this //Will not throw error in case bid does not exist
      }

    def initAuction(item: AuctionableItem, auctionId: Id) = 
      this.copy(item=item, auctionId=auctionId)

    //Should not be Val as item is null on start => will create nullPtrExcept
    def maxBid() = 
      if (!bids.isEmpty)
        (item.itemType, bids.head.bid.amount)
      else 
        (item.itemType, item.startingPrice)


    def applyEvent(event: Event) = 
      event match {
        case AuctionCreated(item, ref) => initAuction(item, ref)
        case BidPlaced(bid, owner) => placeBid(BidState(bid, owner))
        case RemovedBid(bidder) => removeBid(bidder)
        // case AuctionDeleted(_) => ???
        // case AckAuction(_) => ???  
        // case NewBidPlaced(bid) => placeBid(bid)
      }


  private object State {
    val default = State(
      item = null.asInstanceOf[AuctionableItem],
      auctionId = null.asInstanceOf[Id],
      bids = TreeSet.empty
    )
  }


  def apply(item: AuctionableItem, ownerRef: ActorRef[Seller.Command]): Behavior[Command] =
    Behaviors.setup { context =>

      import scala.concurrent.duration.DurationInt
      implicit val timeout: Timeout = 3.seconds

      val auctionId = mkUUID()

      context.log.info(s"Created Auction Manager")
      context.self ! Init(item, ownerRef)

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
          context.log.info(s"Processing ${command}")
          command match {

            case GetMaxBid(replyTo) => { 
              val (item, price) = state.maxBid()
              replyTo ! AuctionList.ItemListing(item, price, context.self)
              Effect.none
            }

            case PlaceBid(bid, replyTo) => {
              if (bid.amount > state.maxBid()._2)
                replyTo ! Bidder.Reply(StatusCode.Failed, "Bid needs to be higher than current one")
                Effect.none
              else
                Effect.persist(BidPlaced(bid, replyTo)).thenReply(replyTo)(_ => Bidder.Reply(StatusCode.OK, "Bid Placed"))
            }

            case CancelBid(bidder, replyTo) => Effect.persist(RemovedBid(bidder)).thenReply(replyTo)(_ => Bidder.Reply(StatusCode.OK, "Bid Deleted"))

            case Init(item, replyToSeller) => {
              try {
                require(item.startingPrice > 0, "Price cannot be negative")
                //Checks passed, retreive EbayRef
                context.ask(context.system.receptionist, Receptionist.Find(eBay.Key)) {
                  case Success(listing: Receptionist.Listing) =>  {
                    context.log.info(s"Got Response: ${listing}")
                    val serviceInstances = listing.serviceInstances(eBay.Key)
                    ReceptionistEbay(serviceInstances.headOption, replyToSeller)
                  }
                  case Failure(exception) => {
                    context.log.error("Failed to get response from Receptionist", exception)
                    ReceptionistEbay(None, replyToSeller)
                  }
                }
                Effect.persist(AuctionCreated(item, Id(auctionId)))
                  .thenReply(replyToSeller)(_ => Seller.Reply(StatusCode.OK, "Auction Input Valid"))
              }
              catch {
                case e: Exception => Effect.none.thenReply(replyToSeller)(_ => Seller.Reply(StatusCode.Failed, e.getMessage()))
              }
            }

            case ReceptionistEbay(maybeEbayRef, sellerRef) => {
              maybeEbayRef match {
                case Some(ref) => {
                  context.log.info("Sent Registration Request")

                  //TODO: Kinda assumed to always succeed, eBay does not respond to Auction directly => What happens
                  //if ebay is not available directly: Should retry for a certain time, and then destroy auction in 
                  //case and notify Seller
                  ref ! eBay.RegisterAuction(eBay.AuctionListing(state.auctionId, context.self), sellerRef) //Seller Ref for Forward Flow
                }
                case None => context.log.error("eBay not registered")
              }
              Effect.none
            }

            // case Remove(seller, replyTo ) => {
            //   seller match {
            //     case Info(_, id) if id == state.item.owner.id =>  ???
            //   }
            // }

            case BankResponse(_) => ???
          }
        }

      def eventHandlerImpl(state: State, event: Event): State = {
          val updatedResult = state.applyEvent(event)
          context.log.info("Current state of the data: {}", updatedResult)
          updatedResult
      }


      EventSourcedBehavior[Command, Event, State] (
        persistenceId = PersistenceId.ofUniqueId(auctionId.toString()),
        emptyState = State.default, 
        commandHandler = (state, command) => commandHandlerImpl(state, command),
        eventHandler = (state, event) => eventHandlerImpl(state, event)
      )
    }
