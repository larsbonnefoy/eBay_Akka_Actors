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
  final case class Id(id: UUID)
  case class Info(usr: User, id: Id)

/*****Bidder Protocol********/
  sealed trait Message

  sealed trait Command extends Message
  case class Create(usr: User, id: Id) extends Command
  case class PlaceBid(amount: Int, auction: ActorRef[Auction.Command]) extends Command
  case class RemoveBid(auction: ActorRef[Auction.Command]) extends Command
  case class GetAuctions(replyTo: ActorRef[eBayMainActor.Protocol]) extends Command
  case class Reply(code: StatusCode, msg: String) extends Command
  case class AuctionListResult(auctions: List[DisplayableAuction]) extends Command

  sealed trait Event 
  case class Created(newUser: User, newId: Id) extends Event
  case class AddedBank(userWithBank: User) extends Event

  final private case class State(user: User, id: Id) {
    def setBank(userWithBank: User) = this.copy(user = userWithBank)

    def initBidder(newUser: User, newId: Id) = 
      this.copy(user = newUser, id = newId)

    def applyEvent(event: Event) = 
      event match {
        case Created(newUser, newId) => initBidder(newUser, newId)
        case AddedBank(userWithBank) => setBank(userWithBank)
      }

  }

  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class BankResponse(res: BankGateway.Response) extends Command
  private case class AuctionPublish(event: Auction.Publish) extends Command
  private case object Ignore extends Command
  private case class GotBank(usr: User) extends Command
  private case class SendToMain(auctions: List[DisplayableAuction], replyTo: ActorRef[eBayMainActor.Protocol]) extends Command


  def apply(user: User, ebayRef: ActorRef[eBay.Command]): Behavior[Command] = {
    Behaviors.setup { context =>
      val id = mkUUID()

      context.self ! Bidder.Create(user, Id(id))

      val listingAdapter = context.messageAdapter[Receptionist.Listing](rsp => ListingResponse(rsp))
      val auctionPublishAdapter = context.messageAdapter[Auction.Publish](rsp => AuctionPublish(rsp))
      val bankAdapter = context.messageAdapter[BankGateway.Response](rsp => BankResponse(rsp))

      import scala.concurrent.duration.DurationInt
      implicit val timeout: Timeout = 3.seconds

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {

          case Create(newUser, id) => {
            context.system.receptionist ! Receptionist.Find(BankGateway.Key, listingAdapter)
            Effect.persist(Created(newUser, id))
          }

          case AuctionPublish(event) => {
            context.log.info(s"Got ${event} from Auction")
            event match {
              case Auction.NewMaxBid(id, price, item) => context.log.info(s"Auction ${id} on ${item} has a new max price: ${price}")
              case Auction.Msg(reason) => context.log.info(reason)
              case Auction.AuctionSold(id) => context.log.info(s"Auction ${id} has been sold")
            }
            Effect.none
          }

          //FIX: Getting a new bankAccount is duplicated in Bidder and Seller, should be moved to another actor
          /*
            NOTE: Weird Impl
            When registering user we need to send an ActorRef so that the bank can contact the seller later, once auction is finished
            Using the context.ask, replyTo is temporary => Need to send 2 actors refs, one temp, one longer lived
            1. Either we use only bankAdapter, this removes the implict request timeout from context.ask and puts handeling code lower
            2. Give 2 refs to RegisterUser, one Temp, and one longer lived
          */
          case ListingResponse(BankGateway.Key.Listing(listings)) => {
            listings.headOption match {
              case Some(bankGateWayRef) => {
                context.ask(bankGateWayRef, (replyTo => BankGateway.RegisterUser(state.user, replyTo, bankAdapter))) {
                  case scala.util.Success(BankGateway.RegistrationSuccessful(usr)) => GotBank(usr)
                  case arg @ scala.util.Failure(_) => context.log.error(s"Did not receive BankGateway.RegistrationSuccessful in time: ${arg}"); Ignore
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
              context.self,
              auctionPublishAdapter)
            Effect.none
          }

          case RemoveBid(auction) => {
            auction ! Auction.CancelBid(
              Bidder.Info(state.user, state.id), 
              context.self)
            Effect.none
          }

          case GotBank(usr) => 
            context.log.info(s"Got Account from Bank ${usr}, This = ${state.user}")
            Effect.persist(AddedBank(usr))

          case BankResponse(res) => {
            res match {
              case BankGateway.TransactionAckQuery(replyTo) => replyTo ! BankGateway.TranscationAckResponse(StatusCode.OK)
              case msg @ _ => context.log.error(s"got unexpected message ${msg}")
            }
            Effect.none
          }

          case AuctionListResult(_) => ???
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
        emptyState = State(null.asInstanceOf[User], null.asInstanceOf[Id]),
        commandHandler = (state, cmd) => commandHandlerImpl(state, cmd),
        eventHandler = (state, event) => eventHandlerImpl(state, event)
      ).onPersistFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))

      }
  }
