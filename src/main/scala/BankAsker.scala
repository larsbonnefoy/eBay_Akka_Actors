package eBayMicroServ

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import java.util.UUID

trait BankAskerCommands
case class StartRegistration() extends BankAskerCommands


private object ResponseHandlerChild:
  def apply(corrID: UUID, replyTo: ActorRef[SellerAction]): Behavior[BankManagerResponse] = {
    Behaviors.setup { context => 
      Behaviors.receive { (context, message) =>
        message match {
          case BankManagerEvent(id, event) => 
            event match {
              case NewUserAccount(userWithAccount) => replyTo ! AuthSeller(userWithAccount)
            }
          case BankManagerRejection(id, reason) => context.log.error("Could not Auth User")
        }
        Behaviors.stopped
      }
    }
  }


object BankAsker:
  private final case class WrappedReceptionistRes(
      bankRef: Option[ActorRef[BankManagerInput]]
  ) extends BankAskerCommands

  def apply(user: User, replyTo: ActorRef[SellerAction]): Behavior[BankAskerCommands] = {
    Behaviors.setup { context =>

      val bankRefMapper: ActorRef[Receptionist.Listing] =
        context.messageAdapter { case BankGateway.Key.Listing(set) =>
          WrappedReceptionistRes(set.headOption)
        }

      context.system.receptionist ! Receptionist.Find(
        BankGateway.Key,
        bankRefMapper
      )

      Behaviors.receive { (context, message) =>
        message match {
          case WrappedReceptionistRes(optionRef) => {
            optionRef match {
              case Some(bankRef) => {
                context.log.info(s"Got ${bankRef}")
                val corrId = mkUUID()
                val childActor = context.spawnAnonymous(ResponseHandlerChild(corrId, replyTo))
                bankRef ! BankManagerCommand(RegisterUser(user), corrId, childActor)
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
