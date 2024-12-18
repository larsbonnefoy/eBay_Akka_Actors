package eBayMicroServ

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AbstractBehavior
import java.util.UUID
import akka.actor.typed.ActorRef

case class SellerRef(id: UUID)

sealed trait SellerAction
case class CreateAuction(item: String) extends SellerAction
case class DeleteAuction(item: String) extends SellerAction

//Seller has: Name, SellerRef, BankId, bankRef (used to contact bank + pass in to auction)
object Seller:
  private case class SellerState(
      user: User,
      sellerRef: SellerRef,
      bankRef: ActorRef[BankManagerInput]
  )

  private final case class WrappedBankResponse(resp: BankManagerResponse) extends SellerAction

  def apply(user: User, bankRef: ActorRef[BankManagerInput]): Behavior[SellerAction] =
    Behaviors.setup { context =>

      val bankResponseMapper: ActorRef[BankManagerResponse] = context.messageAdapter(rsp => WrappedBankResponse(rsp))

      //Send request to bank to get Bank Account to get account
      bankRef ! BankManagerCommand(RegisterUser(user), mkUUID(), bankResponseMapper)

      def bankResponseHandler(resp: BankManagerResponse, state: SellerState) = 
        context.log.info(s"${resp}");
        resp match {
          case BankManagerEvent(id, event) => {
            event match {
              case NewUserAccount(userWithAccount) => {
                active(state.copy(user = userWithAccount)) // update state with bank account
              }
            }
          }
        }

      def active(state: SellerState): Behavior[SellerAction] = {
        Behaviors.receive { (context, message) =>
          message match {

            case CreateAuction(item) => {
              state.user.bank match { 
                case Some(BankAccount(_)) => context.log.info("User has bank account => OK"); Behaviors.same
                case None => context.log.error("User has no Bank account"); Behaviors.same
              }
            }

            case DeleteAuction(item) => ???

            case WrappedBankResponse(resp) => bankResponseHandler(resp, state)
          }
        }
      }

      active(SellerState(user, SellerRef(mkUUID()), bankRef))
  }
