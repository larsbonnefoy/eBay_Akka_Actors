package eBayMicroServ

import akka.actor.Actor
import akka.actor.typed.ActorSystem
import scala.collection.mutable.HashMap
import java.util.UUID
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.persistence.typed.scaladsl.EventSourcedBehavior


case class BankClient(name: String, balance: Int)
case class BankAccount(accLiteral: String)

sealed trait BankMessage

sealed trait BankCommand extends BankMessage
case class RegisterUser(newUser: User) extends BankCommand

sealed trait  BankEvent extends BankMessage
case class NewUserAccount(user: User) extends BankEvent

trait BankGatewayInput 
case class BankGatewayCommand(cmd: BankCommand, id: UUID, replyTo: ActorRef[BankGatewayResponse]) extends BankGatewayInput
// case class BankManagerQuery(cmd: BankCommand, replyTo: ActorRef[BankManagerResponse]) extends BankManagerInput

trait BankGatewayResponse
case class BankGatewayEvent(id: UUID, event: BankEvent) extends BankGatewayResponse
case class BankGatewayRejection(id: UUID, reason: String) extends BankGatewayResponse


/**
 * Manages clients via a Map of BankId -> BankClient
  */
private object Bank {

  // Keeps tract of generated accounts
  var cntr = 0

  // Use hash map to mimic DB
  val store = HashMap[BankAccount, BankClient]()
  
  /**
    * Provides user with a bank account and credits created acccount.
    * Makes more sense that Bank keeps track of attributes account numbers
    * @return Created Bank Id
    */
  def registerUser(newUser: User): BankAccount = {
    cntr += 1
    val newAcc = BankAccount(s"BE${cntr}")
    store += (newAcc -> BankClient(newUser.name, 10))
    newAcc
  }
}


object BankGateway: 
  val Key: ServiceKey[BankGatewayInput] = ServiceKey("BankManagerInput")
  def apply(): Behavior[BankGatewayInput] = 
    Behaviors.setup { context =>
      //register to the receptionist
      context.system.receptionist ! Receptionist.Register(Key, context.self)

      def active(): Behavior[BankGatewayInput] = {
        Behaviors.receive { (context, message) =>
          message match {
            case BankGatewayCommand(cmd, id, replyTo) => 
              try 
                val event = cmd match {
                  case RegisterUser(newUser) => 
                    val acc = Bank.registerUser(newUser)
                    context.log.info(s"Created account ${acc} for user ${newUser}")
                    NewUserAccount(newUser.copy(bank=Some(acc)))
                }
                replyTo ! BankGatewayEvent(id, event)
              catch 
                case _ =>  replyTo ! BankGatewayRejection(id, "Rejection From Bank: Catch All except")
          }
          Behaviors.same
        }
      }
      active()
    }


/* object BankManagerMain { 
  val usr1 = User("Lars", None)
  val usr2 = User("Lars2", None)
  def mkUUID() = UUID.randomUUID() //Used from message correlations ids

  def apply(): Behavior[BankManagerResponse] = 
    Behaviors.setup { context =>
      val bankManager = context.spawn(BankManager(), "bankManager")

      bankManager ! BankManagerCommand(RegisterUser(usr1), mkUUID(), context.self)
      bankManager ! BankManagerCommand(RegisterUser(usr2), mkUUID(), context.self)

      Behaviors.receiveMessage { message => 
        message match {
          case BankManagerEvent(id, event) => 
            context.log.info("success ({}): {}", id, event)
            Behaviors.same
          case BankManagerRejection(id, reason) => 
            context.log.info("failure ({}): {}", id, reason)
            Behaviors.same
        }
      }
    }
}

@main
def BankMain() = ActorSystem(BankManagerMain(), "BankManagerMain") */
