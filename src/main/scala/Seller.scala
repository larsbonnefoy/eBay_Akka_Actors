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
case class AuthSeller(usr: User) extends SellerAction

//Seller has: Name, SellerRef, BankId, bankRef (used to contact bank + pass in to auction)
object Seller:

  private final case class WrappedBankResponse(resp: BankManagerResponse) extends SellerAction
  private final case class WrappedAuctionResponse(resp: AuctionEvent) extends SellerAction
  private final case class WrappedReceptionistRes(bankRef: Option[ActorRef[BankManagerInput]]) extends SellerAction
  private final case class WrappedEbayRes(ebayRef: ebayEvent) extends SellerAction

  def apply(user: User): Behavior[SellerAction] = {
    Behaviors.setup { context =>

      val bankResponseMapper: ActorRef[BankManagerResponse] = context.messageAdapter(rsp => WrappedBankResponse(rsp))
      val bankRefMapper: ActorRef[Receptionist.Listing] = context.messageAdapter { 
        case BankGateway.Key.Listing(set) => WrappedReceptionistRes(set.headOption)
      }
      val auctionMapper: ActorRef[AuctionEvent] = context.messageAdapter(rsp => WrappedAuctionResponse(rsp))

      val ebayMapper: ActorRef[ebayEvent] = context.messageAdapter(rsp  => WrappedEbayRes(rsp))


      // First need to retrieve ActorRef of Bank
      // Receptionist makes them more independent, i dont need to pass in Bank ref as 
      // member of protocol, <> adds one layer of messages
      context.system.receptionist ! Receptionist.Find(BankGateway.Key, bankRefMapper)


      def bankResponseHandler(resp: BankManagerResponse, seller: Seller, bankRef: Option[ActorRef[BankManagerInput]]) = 
        resp match {
          case BankManagerEvent(id, event) => {
            event match {
              case NewUserAccount(userWithAccount) => {
                val newSeller = Seller(userWithAccount, seller.sellerId)
                active(newSeller, bankRef) // update state with bank account
              }
            }
          }
        }

      def active(seller: Seller, bankRef: Option[ActorRef[BankManagerInput]]): Behavior[SellerAction] = {
        Behaviors.receive { (context, message) =>
          message match
            case CreateAuction(price, item) => {
              seller.user.bank match { 
                case Some(BankAccount(_)) => {
                  val auction = context.spawnAnonymous(PersistentAuctionManager())
                  context.log.info(s"Send init request for auction ${auction}")
                  auction ! InitAuction(AuctionableItem(seller, price, item), auctionMapper, ebayMapper)
                  active(seller, bankRef)
                }
                case None => context.log.error(s"User has no Bank account ${seller}"); Behaviors.stopped
              }
            }

            case DeleteAuction(item) => ???

            case WrappedBankResponse(resp) => bankResponseHandler(resp, seller, bankRef)

            case WrappedReceptionistRes(optionRef) => {
              optionRef match {
                case Some(bankRef) => {
                  bankRef ! BankManagerCommand(RegisterUser(user), mkUUID(), bankResponseMapper) 
                  active(seller, optionRef)
                }
                case None => context.log.error("Could not retreive Bank Actor"); Behaviors.stopped
              }
            }

            case WrappedAuctionResponse(ack) => context.log.info(s"Received ${ack} from Auction"); active(seller, bankRef)

            case WrappedEbayRes(ack) => context.log.info(s"Received ${ack} from eBay"); active(seller, bankRef)

          }
        }
      active(Seller(user, SellerRef(mkUUID())), None)
      }
  }
