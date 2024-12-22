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
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.actor.typed.SupervisorStrategy
import akka.util.Timeout
import _root_.eBayMicroServ.Auction.PlaceBid

final case class DisplayableAuction(item: String, price: Int, auctionRef: ActorRef[Auction.Command])
case class Bid(bidder: Bidder.Info, amount: Int)

object Bidder:
  final case class BidderId(id: UUID)
  case class Info(usr: User, id: BidderId)

/*****Bidder Protocol********/
  sealed trait Message

  sealed trait Command extends Message
  case class Create(usr: User, id: BidderId) extends Command
  case class PlaceBid(amount: Int, auction: ActorRef[Auction.Command]) extends Command
  case class RemoveBid(auction: ActorRef[Auction.Command]) extends Command
  case class GetAuctions(replyTo: ActorRef[eBayMainActor.Protocol]) extends Command
  case class Reply(code: StatusCode, msg: String) extends Command
  case class AuctionListResult(auctions: List[DisplayableAuction]) extends Command

  sealed trait Event 
  case class Created(newUser: User, newId: BidderId) extends Event
  case class AddedBank(userWithBank: User) extends Event

  final private case class State(user: User, id: BidderId) {
    def setBank(userWithBank: User) = this.copy(user = userWithBank)

    def initBidder(newUser: User, newId: BidderId) = 
      this.copy(user = newUser, id = newId)

    def applyEvent(event: Event) = 
      event match {
        case Created(newUser, newId) => initBidder(newUser, newId)
        case AddedBank(userWithBank) => setBank(userWithBank)
      }

  }

  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class AuctionEvent(event: Auction.Event) extends Command
  private case object Ignore extends Command
  private case class GotBank(usr: User) extends Command
  private case class SendToMain(auctions: List[DisplayableAuction], replyTo: ActorRef[eBayMainActor.Protocol]) extends Command


  def apply(user: User, ebayRef: ActorRef[eBay.Command]): Behavior[Command] = {
    Behaviors.setup { context =>
      val id = mkUUID()

      context.self ! Bidder.Create(user, BidderId(id))

      val listingAdapter = context.messageAdapter[Receptionist.Listing](rsp => ListingResponse(rsp))
      val auctionEventAdapter = context.messageAdapter[Auction.Event](rsp => AuctionEvent(rsp))

      import scala.concurrent.duration.DurationInt
      implicit val timeout: Timeout = 3.seconds

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {

          case Create(newUser, id) => {
            context.system.receptionist ! Receptionist.Find(BankGateway.Key, listingAdapter)
            Effect.persist(Created(newUser, id))

          }

          case ListingResponse(BankGateway.Key.Listing(listings)) => {
            listings.headOption match {
              case Some(bankGateWayRef) => {
                context.ask(bankGateWayRef, (replyTo: ActorRef[BankGatewayResponse]) => BankGatewayCommand(RegisterUser(state.user), mkUUID(), replyTo)) {
                  case scala.util.Success(BankGatewayEvent(id, event)) => {
                    event match
                      case NewUserAccount(user) => GotBank(user)
                    }
                  case arg @ scala.util.Failure(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                  case arg @ scala.util.Success(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                }
                Effect.none
              }
              case None => context.log.error("Error with Receptionist Listing, Got None"); Effect.none
            }
          }

          case Ignore => Effect.none

          case Reply(code, msg) => context.log.info(s"${code}: ${msg}"); Effect.none

          case GetAuctions(replyTo) => {
            state.user.bank match {
              case Some(_) => {
                //PB with ask is that it will shutdown on the first answer it receives
                // => Cannot be used if we get intermediat answers as it will terminate the actor
                // and subsequent request wont be delivered.
                context.ask(ebayRef, replyTo => eBay.AvailableAuctions(replyTo)) {
                  case scala.util.Success(Bidder.AuctionListResult(lst)) => { 
                    SendToMain(lst, replyTo)
                  }
                  case arg @ scala.util.Failure(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                  case arg @ scala.util.Success(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                }
              }
              case None => context.log.error("User has no Bank Account")
            }
            Effect.none
          }

          case SendToMain(lst, replyTo) => {
            replyTo ! eBayMainActor.AuctionList(lst)
            Effect.none
          }

          case PlaceBid(amount, auction) => {
            auction ! Auction.PlaceBid(
              Bid(Bidder.Info(state.user, state.id), amount), 
              context.self)
            Effect.none
          }

          case RemoveBid(auction) => {
            auction ! Auction.CancelBid(
              Bidder.Info(state.user, state.id), 
              context.self)
            Effect.none
          }

          case GotBank(usr) => 
            context.log.info(s"Got Account from Bank ${usr}")
            Effect.persist(AddedBank(usr))

          case AuctionListResult(_) => context.log.info("HERE1"); ???
          case ListingResponse(_) => ???
        }
      }

      def eventHandlerImpl(state: State, event: Event): State = {
          val updatedResult = state.applyEvent(event)
          context.log.info("Current state of the data: {}", updatedResult)
          updatedResult
      }

      import scala.concurrent.duration.DurationInt

      EventSourcedBehavior[Command, Event, State] (
        persistenceId = PersistenceId.ofUniqueId(id.toString()),
        emptyState = State(null.asInstanceOf[User], null.asInstanceOf[BidderId]),
        commandHandler = (state, cmd) => commandHandlerImpl(state, cmd),
        eventHandler = (state, event) => eventHandlerImpl(state, event)
      ).onPersistFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))

      }
  }
