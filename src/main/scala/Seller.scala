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

final case class SellerRef(id: UUID)
final case class Seller(user: User, sellerId: SellerRef)
final case class AuctionableItem(owner: Seller, startingPrice: Int, item : String)

/*****Seller Protocol********/
sealed trait SellerAction
case class CreateAuction(startingPrice: Int, item: String) extends SellerAction
case class DeleteAuction(item: String) extends SellerAction

object Seller:

  private final case class WrappedAuctionResponse(resp: AuctionEvent) extends SellerAction
  private final case class WrappedEbayRes(ebayRef: ebayEvent) extends SellerAction
  private final case class WrappedAuth(resp: AuthUser) extends SellerAction

  def apply(user: User): Behavior[SellerAction] = {
    Behaviors.setup { context =>

      val auctionMapper: ActorRef[AuctionEvent] = context.messageAdapter(rsp => WrappedAuctionResponse(rsp))
      val ebayMapper: ActorRef[ebayEvent] = context.messageAdapter(rsp  => WrappedEbayRes(rsp))
      val authMapper: ActorRef[AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))

      val asker = context.spawnAnonymous(BankAsker())
      asker ! StartRegistration(user, authMapper)

      def active(seller: Seller): Behavior[SellerAction] = {
        Behaviors.receive { (context, message) =>
          message match
            case CreateAuction(price, item) => {
              seller.user.bank match { 
                case Some(BankAccount(_)) => {
                  val auction = context.spawnAnonymous(PersistentAuctionManager())
                  auction ! InitAuction(AuctionableItem(seller, price, item), auctionMapper, ebayMapper)
                  active(seller)
                }
                case None => context.log.error(s"User has no Bank account ${seller}"); Behaviors.stopped
              }
            }

            case DeleteAuction(item) => ???

            case WrappedAuth(auth) => {
              context.log.info(s"Got Account from Bank ${auth.user}") 
              active(seller.copy(user = auth.user))
            }

            case WrappedAuctionResponse(ack) => context.log.info(s"Received ${ack} from Auction"); active(seller)

            case WrappedEbayRes(ack) => context.log.info(s"Received ${ack} from eBay"); active(seller)

          }
        }
      active(Seller(user, SellerRef(mkUUID())))
      }
  }
