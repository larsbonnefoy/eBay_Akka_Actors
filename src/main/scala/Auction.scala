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
import akka.actor.Actor
import scala.util.Success
import scala.util.Try
import scala.util.Failure
import akka.util.Timeout

//Bid will probably need to be exposed so that a Bidder can place a Bid
//Should probably go into the Bidder Class
//case class Bid(bidder: String, amount: Int)

//
//TODO: reply to Aggregator

object Auction:
  case class Id(id: UUID)

  trait Message
  sealed trait Command extends Message
  case class Remove() extends Command
  case class PlaceBid(bid: Bid) extends Command
  case class GetMaxBid(replyTo: ActorRef[AuctionList.Aggregator.Result]) extends Command

  sealed trait Event extends Message
  case class AuctionCreated(item: AuctionableItem, auctionId: Id) extends Event
  // case class AuctionDeleted(acc: Auction) extends AuctionEvent
  // case class NewBidPlaced(bid: Bid) extends AuctionEvent
  // case class AckAuction(msg: String) extends AuctionEvent

  /**Internal Protocol**/
  implicit private val bidOrdering: Ordering[Bid]= Ordering.by(-_.amount)
  private case class Init(item: AuctionableItem, replyTo: ActorRef[Seller.Command]) extends Command //-> Dont need this message as the auction is created when Actor is initalized
  private final case class BankResponse(resp: BankGatewayResponse) extends Command
  private final case class ReceptionistEbay(bankRef: Option[ActorRef[eBay.Command]], replyTo: ActorRef[Seller.Command]) extends Command

  private case class State(item: AuctionableItem, auctionId: Id, bids: TreeSet[Bid]):

    private def currPrice : Int = bids.headOption match { 
      case Some(currentMaxBid) => currentMaxBid.amount
      case None => item.startingPrice
    }

    def placeBid(bid: Bid) = 
      //require(bid.amount > currPrice, s"Bid is too low. Proposed price: ${bid.amount}, Current price: ${currPrice}")
      this.copy(bids = bids + bid)

    def initAuction(item: AuctionableItem, auctionId: Id) = 
      this.copy(item=item, auctionId=auctionId)

    //Should not be Val as item is null on start => will create nullPtrExcept
    def maxBid() = 
      if (!bids.isEmpty)
        (item.itemType, bids.head.amount)
      else 
        (item.itemType, item.startingPrice)


    def applyEvent(event: Event) = 
      event match {
        case AuctionCreated(item, ref) => initAuction(item, ref)
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

      //Different actor references are kept within the Manager 
      //and not the state

      // val ebayReceptionistMapper: ActorRef[Receptionist.Listing] = context.messageAdapter { 
      //   case PersistentEbayManager.Key.Listing(set) => ReceptionistEbay(set.headOption)
      // }
      context.log.info(s"Created Auction Manager")
      context.self ! Init(item, ownerRef)

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
          context.log.info(s"Processing Command ${command}")
          command match {
            case GetMaxBid(replyTo) => { 
              val (item, price) = state.maxBid()
              replyTo ! AuctionList.Aggregator.ItemListing(item, price, context.self)
              Effect.none
            }
            // case PlaceBid(bid) => ???
            // case RemoveAuction() => ???
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
            // case WrappedBankResponse(_) => ???

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
            case Remove() => ???
            //TODO: Should check here that price is above current price
            case PlaceBid(_) => ??? 
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
