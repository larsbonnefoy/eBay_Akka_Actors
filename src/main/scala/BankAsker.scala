package eBayMicroServ

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import java.util.UUID

sealed trait AuthBank
case class AuthUser(user: User) extends AuthBank

sealed trait BankAskerCommands
case class StartRegistration(user: User, replyTo: ActorRef[AuthUser]) extends BankAskerCommands

private object ResponseHandlerChild:
  def apply(corrID: UUID, replyTo: ActorRef[AuthUser]): Behavior[BankGatewayResponse] = {
    Behaviors.setup { context => 
      Behaviors.receive { (context, message) =>
        message match {
          case BankGatewayEvent(id, event) => 
            event match {
              case NewUserAccount(userWithAccount) => replyTo ! AuthUser(userWithAccount)
            }
          case BankGatewayRejection(id, reason) => context.log.error("Could not Auth User")
        }
        Behaviors.stopped
      }
    }
  }


object BankAsker:
  private final case class WrappedReceptionistRes(bankRef: Option[ActorRef[BankGatewayInput]]) extends BankAskerCommands

  def apply(): Behavior[BankAskerCommands] = {
    Behaviors.setup { context =>

      val bankRefMapper: ActorRef[Receptionist.Listing] = context.messageAdapter { 
        case BankGateway.Key.Listing(set) => WrappedReceptionistRes(set.headOption) }

      var replyTo: Option[ActorRef[AuthUser]] = None
      var user: Option[User] = None

      Behaviors.receive { (context, message) =>
        message match {
          case StartRegistration(userFromMsg, replyToFromMsg) => {
            context.system.receptionist ! Receptionist.Find(BankGateway.Key, bankRefMapper)
            replyTo = Some(replyToFromMsg)
            user = Some(userFromMsg)
          }
          case WrappedReceptionistRes(optionRef) => {
            optionRef match {
              case Some(bankRef) => {
                context.log.info(s"Got ${bankRef}")
                val corrId = mkUUID()
                val childActor = context.spawnAnonymous(ResponseHandlerChild(corrId, replyTo.get))
                bankRef ! BankGatewayCommand(RegisterUser(user.get), corrId, childActor)
                // bankRef ! BankManagerCommand(RegisterUser(user), mkUUID(), bankResponseMapper)
              }
              case None =>
                context.log.error("Could not retreive Bank Actor");
                Behaviors.stopped
            }
          }
        }
        Behaviors.same
      }
    }
  }
