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

  private final case class WrappedAuth(resp: BankAuth.AuthUser) extends Command
  private case class Init(usr: User, id: SellerId) extends Command

  def apply(user: User): Behavior[Command] = {
    Behaviors.setup { context =>
      val id = mkUUID()

      context.self ! Seller.Init(user, SellerId(id))

      val authMapper: ActorRef[BankAuth.AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {
          case Init(newUser, id) => {
            val asker = context.spawnAnonymous(BankAuth())
            asker ! BankAuth.StartRegistration(newUser, authMapper)
            Effect.persist(Created(newUser, id))
          }
          case WrappedAuth(resp) => {
            context.log.info(s"Got Account from Bank ${resp}")
            Effect.persist(AddedBank(resp.user))
          }
          case CreateAuction(item, price) => {
            val aucItem = AuctionableItem(Info(state.user, state.id), price, item)
            val auc = context.spawnAnonymous(Auction(aucItem, context.self))
            Effect.none
          }
          //   auction = context.spawnAnonymous(Auction())
          //
          //
          // }
          case Reply(code, msg) => context.log.info(s"${code}: ${msg}"); Effect.none
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
