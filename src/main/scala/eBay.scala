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


object eBay:
  val Key: ServiceKey[Command] = ServiceKey("ebayCommand")
  final case class AuctionListing(auction: Auction.Id, auctionRef: ActorRef[Auction.Command])

  /**eBay Protocol**/
  trait ebayMessage
  sealed trait Command extends ebayMessage
  case class RegisterAuction(listing: AuctionListing, replyTo: ActorRef[Seller.Command]) extends Command
  case class AvailableAuctions(replyTo: ActorRef[Bidder.Command]) extends Command

  sealed trait Event extends ebayMessage
  case class RegisteredAuction(aucc: AuctionListing) extends Event
  case class ebayAck(msg: String) extends Event


  private case class State(auctions: Set[AuctionListing]):
    def addAuction(auc: AuctionListing) = this.copy(auctions = auctions + auc)

    def applyEvent(event: Event) = {
      event match {
        case RegisteredAuction(auction) => addAuction(auction)
      }
    }
  def apply(): Behavior[Command] =
    Behaviors.setup { context =>

      // Need to register to the Receptionist
      context.system.receptionist ! Receptionist.Register(Key, context.self)

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("eBay"),
        emptyState = State(Set.empty),
        commandHandler = { (state, command) =>
          context.log.info(s"Processing ${command}")
          command match {
            case RegisterAuction(aucc, replyTo) =>
              Effect
                .persist(RegisteredAuction(aucc))
                .thenReply(replyTo)(_ =>
                  Seller.Reply(StatusCode.OK, "Auction Registered Successfully")
                )
            case AvailableAuctions(replyTo) => {
              val refSet = state.auctions.map(auction => auction.auctionRef)
              if (refSet.isEmpty)
                Effect.none.thenReply(replyTo)(_ => Bidder.Reply(StatusCode.Failed, "No Bids available"))
              else
                val list = context.spawnAnonymous(AuctionList())
                list ! AuctionList.Get(refSet, context.self)
                Effect.none.thenReply(replyTo)(_ => Bidder.Reply(StatusCode.OK, "Sent request to aggreg"))
            }
          }
        },
        eventHandler = { (state, event) =>
          val updatedResult = state.applyEvent(event)
          context.log.info("Current state of the data: {}", updatedResult)
          context.log.info(
            s"Number of Auctions: ${updatedResult.auctions.size}"
          )
          updatedResult
        }
      )
    }
