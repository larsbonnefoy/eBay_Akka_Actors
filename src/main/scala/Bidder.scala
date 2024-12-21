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

final case class DisplayableAuction(item: String, price: Int, replyTo: ActorRef[Auction.Command])
case class Bid(bidder: Bidder.Info, amount: Int)

object Bidder:
  final case class BidderId(id: UUID)
  case class Info(usr: User, id: BidderId)

/*****Bidder Protocol********/
  sealed trait Message

  sealed trait Command extends Message
  case class Create(usr: User, id: BidderId) extends Command
  case class GetAuctions() extends Command
  case class Reply(code: StatusCode, msg: String) extends Command
  case class AuctionsListResult(auctions: List[DisplayableAuction]) extends Command

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

  private final case class WrappedAuth(resp: BankAuth.AuthUser) extends Command

  def apply(user: User, ebayRef: ActorRef[eBay.Command]): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info(s"Bidder ACTOR: ${context.self}")
      val id = mkUUID()

      context.self ! Bidder.Create(user, BidderId(id))

      val authMapper: ActorRef[BankAuth.AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))

      def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
        command match {
          case Create(newUser, id) => {
            val asker = context.spawnAnonymous(BankAuth())
            asker ! BankAuth.StartRegistration(newUser, authMapper)
            Effect.persist(Created(newUser, id))
          }
          case WrappedAuth(resp) => {
            context.log.info(s"Got Account from Bank ${resp}")
            Effect.persist(AddedBank(resp.user))
          }

          case Reply(code, msg) => context.log.info(s"${code}: ${msg}"); Effect.none

          case GetAuctions() => {
            state.user.bank match {
              case Some(_) => ebayRef ! eBay.AvailableAuctions(context.self)
              case None => context.log.error("User has no Bank Account")
            }
            Effect.none
          }

          case AuctionsListResult(_) => ???
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


// /*****Bidder Protocol********/
//
// sealed trait BidderMessages
// // case class MakeBid() extends BidderAction
// // case class WithdrawBid() extends BidderAction
// sealed trait BidderAction extends BidderMessages
// case class GetAuctions() extends BidderAction
// case class AuctionsListResult(auctions: Seq[DisplayableAuction]) extends BidderAction
// case class BidderAck(code: StatusCode, message: String) extends BidderAction
//
// object Bidder:
//   private final case class WrappedAuth(resp: BankAuth.AuthUser) extends BidderAction
//
//   def apply(user: User, ebayRef: ActorRef[ebayCommand]): Behavior[BidderAction] = {
//     Behaviors.setup { context =>
//
//       val authMapper: ActorRef[BankAuth.AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))
//
//       val asker = context.spawnAnonymous(BankAuth())
//       asker ! BankAuth.StartRegistration(user, authMapper)
//
//       def active(bidder: Bidder): Behavior[BidderAction] = {
//         Behaviors.receive { (context, message) =>
//           context.log.info(s"Processing ${message}")
//           message match {
//             case WrappedAuth(auth) => {
//               context.log.info(s"Got Account from Bank ${auth.user}") 
//               active(bidder.copy(user = auth.user))
//             }
//             case GetAuctions() => 
//               bidder.user.bank match {
//                 case Some(_) => ebayRef ! AvailableAuctions(context.self)
//                 case None => context.log.error("User has no Bank Account")
//               }
//               active(bidder)
//             case AuctionsListResult(res) => context.log.info(s"Got results ${res}"); active(bidder)
//             case BidderAck(code, msg) => {
//               code match {
//                 case StatusCode.OK => context.log.info(s"${code}: ${msg}")
//                 case StatusCode.Failed => context.log.error(s"${code}: ${msg}"); 
//               }
//               active(bidder)
//             }
//           }
//         }
//       }
//       active(Bidder(user, BidderRef(mkUUID())))
//     }
//   }
