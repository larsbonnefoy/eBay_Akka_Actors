package eBayMicroServ

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import scala.collection.immutable.Set
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.receptionist.Receptionist
import akka.pattern.ask

final case class AuctionListing(
    seller: SellerRef,
    auction: AuctionRef,
    aucAccRef: ActorRef[AuctionCommand]
)

trait ebayMessage

sealed trait ebayCommand extends ebayMessage
case class RegisterAuction(aucc: AuctionListing, replyTo: ActorRef[ebayEvent])
    extends ebayCommand
case class AvailableAuctions(replyTo: ActorRef[BidderAction])
    extends ebayCommand

sealed trait ebayEvent extends ebayMessage
case class RegisteredAuction(aucc: AuctionListing) extends ebayEvent
case class ebayAck(msg: String) extends ebayEvent

case class eBay(auctions: Set[AuctionListing]):
  def addAuction(auc: AuctionListing) = this.copy(auctions = auctions + auc)

  def applyEvent(event: ebayEvent) = {
    event match {
      case RegisteredAuction(auction) => addAuction(auction)
    }
  }

object PersistentEbayManager:
  val Key: ServiceKey[ebayCommand] = ServiceKey("ebayCommand")
  def apply(): Behavior[ebayCommand] =
    Behaviors.setup { context =>

      // Need to register to the Receptionist
      context.system.receptionist ! Receptionist.Register(Key, context.self)

      EventSourcedBehavior[ebayCommand, ebayEvent, eBay](
        persistenceId = PersistenceId.ofUniqueId("eBay"),
        emptyState = eBay(Set.empty),
        commandHandler = { (state, command) =>
          command match {
            case RegisterAuction(aucc, replyTo) =>
              Effect
                .persist(RegisteredAuction(aucc))
                .thenReply(replyTo)(_ =>
                  ebayAck("Auction Registered Successfully")
                )
            case AvailableAuctions(replyTo) => {
              context.log.info("Starting Aggreg")
              Effect.none
            }
          }
        },
        eventHandler = { (state, event) =>
          val updatedResult = state.applyEvent(event)
          // context.log.info("Current state of the data: {}", updatedResult)
          context.log.error(
            s"Number of Auctions: ${updatedResult.auctions.size}"
          )
          updatedResult
        }
      )
    }
