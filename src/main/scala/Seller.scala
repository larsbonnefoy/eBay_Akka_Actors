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
  final case class SellerId(id: UUID)
  case class Info(usr: User, id: SellerId)

/*****Seller Protocol********/
  sealed trait Command
  case class CreateAuction(item: String, price: Int) extends Command
  case class Reply(code: StatusCode, msg: String) extends Command

  sealed trait Event 
  case class Created(newUser: User, newId: SellerId) extends Event
  case class AddedBank(userWithBank: User) extends Event

  final private case class State(user: User, id: SellerId) {
    def setBank(userWithBank: User) = this.copy(user = userWithBank)

    def initSeller(newUser: User, newId: SellerId) = 
      this.copy(user = newUser, id = newId)

    def applyEvent(event: Event) = 
      event match {
        case Created(newUser, newId) => initSeller(newUser, newId)
        case AddedBank(userWithBank) => setBank(userWithBank)
      }

  }

  private case class Init(usr: User, id: SellerId) extends Command
  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class GotBank(usr: User) extends Command
  private case object Ignore extends Command

  def apply(user: User): Behavior[Command] = {
    Behaviors.setup { context =>
      import scala.concurrent.duration.DurationInt
      implicit val timeout: Timeout = 3.seconds
      val id = mkUUID()

      context.self ! Seller.Init(user, SellerId(id))

      val listingAdapter = context.messageAdapter[Receptionist.Listing](rsp => ListingResponse(rsp))

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {
          case Init(newUser, id) => {
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
                  case scala.util.Failure(_) => context.log.error("BankGateWay Ask Failed"); Ignore
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
          case ListingResponse(_) => ??? // For some reason Match case is not happy, case is present twice
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
        emptyState = State(null.asInstanceOf[User], null.asInstanceOf[SellerId]),
        commandHandler = (state, cmd) => commandHandlerImpl(state, cmd),
        eventHandler = (state, event) => eventHandlerImpl(state, event)
      ).onPersistFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))

      }
  }
