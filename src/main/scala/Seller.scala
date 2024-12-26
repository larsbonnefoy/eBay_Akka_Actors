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
import scala.util.{Try, Success, Failure}

case class AuctionableItem(owner: Seller.Info, startingPrice: Int, itemType : String)

object Seller:
  final case class Id(id: UUID)
  case class Info(usr: User, id: Id)

/*****Seller Protocol********/
  sealed trait Command
  case class CreateAuction(item: String, price: Int) extends Command
  case class Reply(code: StatusCode, msg: String) extends Command

  sealed trait Event 
  case class Created(newUser: User, newId: Id) extends Event
  case class AddedBank(userWithBank: User) extends Event

  final private case class State(user: User, id: Id) {
    def setBank(userWithBank: User) = this.copy(user = userWithBank)

    def initSeller(newUser: User, newId: Id) = 
      this.copy(user = newUser, id = newId)

    def applyEvent(event: Event) = 
      event match {
        case Created(newUser, newId) => initSeller(newUser, newId)
        case AddedBank(userWithBank) => setBank(userWithBank)
      }

  }

  private case class Init(usr: User, id: Id) extends Command
  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class BankResponse(res: BankGateway.Response) extends Command
  private case class GotBank(usr: User) extends Command
  private case object Ignore extends Command

  def apply(user: User): Behavior[Command] = {
    Behaviors.setup { context =>
      import scala.concurrent.duration.DurationInt
      implicit val timeout: Timeout = 3.seconds
      val id = mkUUID()

      context.self ! Seller.Init(user, Id(id))

      val listingAdapter = context.messageAdapter[Receptionist.Listing](rsp => ListingResponse(rsp))
      val bankAdapter = context.messageAdapter[BankGateway.Response](rsp => BankResponse(rsp))

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {
          case Init(newUser, id) => {
            context.system.receptionist ! Receptionist.Find(BankGateway.Key, listingAdapter)
            Effect.persist(Created(newUser, id))
          }
          case ListingResponse(BankGateway.Key.Listing(listings)) => {
            listings.headOption match {
              case Some(bankGateWayRef) => {

                //FIX: Getting a new bankAccount is duplicated in Bidder and Seller, should be moved to another actor
                /*
                  NOTE: Weird Impl
                  When registering user we need to send an ActorRef so that the bank can contact the seller later, once auction is finished
                  Using the context.ask, replyTo is temporary => Need to send 2 actors refs, one temp, one longer lived
                  1. Either we use only bankAdapter, this removes the implict request timeout from context.ask and puts handeling code lower
                  2. Give 2 refs to RegisterUser, one Temp, and one longer lived
                */
                context.ask(bankGateWayRef, replyTo => BankGateway.RegisterUser(state.user, replyTo, bankAdapter)) {
                  case scala.util.Success(BankGateway.RegistrationSuccessful(usr)) => GotBank(usr)
                  case scala.util.Failure(_) => context.log.error("BankGateWay Ask Failed"); Ignore
                  case arg @ scala.util.Success(_) => context.log.error(s"Got unexpected command from bank ${arg}"); Ignore
                }
                Effect.none
              }
              case None => context.log.error("Error with Receptionist Listing, Got None"); Effect.none
            }
          }
          case Ignore => Effect.none

          case GotBank(usr) =>  {
            context.log.info(s"HERE: Got Account from Bank ${usr}")
            Effect.persist(AddedBank(usr))
          }

          case CreateAuction(item, price) => {
            state.user.bank match {
              case Some(_) => {
                val aucItem = AuctionableItem(Info(state.user, state.id), price, item)
                val auc = context.spawnAnonymous(Auction(aucItem, context.self))
              }
              case None => context.log.error(s"Seller {state.user} as no bankAccount")

            }
            Effect.none
          }
          case Reply(code, msg) => context.log.info(s"${code}: ${msg}"); Effect.none
          case ListingResponse(_) => ???

          case BankResponse(res) => {
            res match {
              case BankGateway.TransactionAckQuery(replyTo) => replyTo ! BankGateway.TranscationAckResponse(StatusCode.OK)
              case msg @ _ => context.log.error(s"got unexpected message ${msg}")
            }
            Effect.none
          } 
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
