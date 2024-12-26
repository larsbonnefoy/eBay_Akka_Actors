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
import scala.util.Success
import scala.concurrent.duration.DurationInt
import akka.util.Timeout
import akka.actor.Status


object eBay:
  val Key: ServiceKey[Command] = ServiceKey("ebayCommand")

  final case class AuctionListing(auction: Auction.Id, auctionRef: ActorRef[Auction.Command])

  /**eBay Protocol**/
  trait ebayMessage
  sealed trait Command extends ebayMessage
  case class RegisterAuction(listing: AuctionListing, replyToSeller: ActorRef[Seller.Command], replyToThis: ActorRef[eBay.Response]) extends Command
  case class AvailableAuctions(replyTo: ActorRef[Bidder.Command]) extends Command

  sealed trait Event extends ebayMessage
  case class RegisteredAuction(aucc: AuctionListing) extends Event
  case class Response(code: StatusCode, msg: String)

  case class AuctionListResult(list: List[DisplayableAuction]) extends Command

  private case object Ignore extends Command
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
            case RegisterAuction(aucc, replyToSeller, replyToAuction) =>
              Effect
                .persist(RegisteredAuction(aucc))
                .thenRun { _ => 
                    replyToAuction ! eBay.Response(StatusCode.OK, "Auction Registered Successfully")
                    replyToSeller ! Seller.Reply(StatusCode.OK, "Auction Registered Successfully")
                }
                // .thenReply(replyToSeller)(_ =>
                //   Seller.Reply(StatusCode.OK, "Auction Registered Successfully")
                // )

            case AvailableAuctions(replyTo) => {
              val refSet = state.auctions.map(auction => auction.auctionRef)
              if (refSet.isEmpty)
                Effect.none.thenReply(replyTo)(_ => Bidder.Reply(StatusCode.Failed, "No Bids available"))
              else
                val auctionListActor = context.spawnAnonymous(AuctionList())
                // Cannot reply to Bidder directly, it will terminate the Ask Pattern
                //replyTo ! Bidder.Reply(StatusCode.OK, "Sent request to aggreg")
                implicit val timeout: Timeout = 3.seconds
                context.ask(auctionListActor, (replyTo: ActorRef[eBay.Command])  => AuctionList.Get(refSet, replyTo)) {
                  case arg @ scala.util.Success(eBay.AuctionListResult(lst)) =>  {
                    replyTo ! Bidder.AuctionListResult(lst)
                    Ignore
                  }
                  case arg @ scala.util.Failure(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                  case arg @ scala.util.Success(_) => context.log.error(s"Got unexpected Argument ${arg}"); Ignore
                }
                Effect.none
                  // list ! AuctionList.Get(refSet, context.self)
                  // Effect.none.thenReply(replyTo)(_ => Bidder.Reply(StatusCode.OK, "Sent request to aggreg"))
            }
            case Ignore => Effect.none
            case AuctionListResult(_) => ???
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
