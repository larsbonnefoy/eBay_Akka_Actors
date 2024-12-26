package eBayMicroServ

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import scala.concurrent.duration.DurationInt
import java.util.UUID
import scala.util.Success
import _root_.eBayMicroServ.StatusCode.Failed
import scala.util.Failure
import akka.util.Timeout

object BankGateway {

  case class BankAccount(accLiteral: String)


  private object BankAccountOrdering extends Ordering[BankAccount] {

  import scala.math.Ordered.orderingToOrdered

    override def compare(a: BankAccount, b: BankAccount): Int = {
      val numA = a.accLiteral.stripPrefix("BE").toInt
      val numB = b.accLiteral.stripPrefix("BE").toInt
      numA.compare(numB)
    }
  }

  // Commands
  sealed trait Command
  final case class RegisterUser(newUser: User, replyToAsk: ActorRef[Response], contact: ActorRef[Response]) extends Command
  final case class PaymentRequest(seller: User, bidder: User, amount: Int, replyTo: ActorRef[Response]) extends Command
  final case class TranscationAckResponse(code: StatusCode) extends Command

  private case object Ignore extends Command
  private case class TransactionFailed(reason: String, replyTo: ActorRef[Response]) extends Command
  private case class TransactionSuccess(seller: User, bidder: User, amount: Int, replyTo: ActorRef[Response]) extends Command

  // Events
  sealed trait Event
  private final case class UserRegistered(user: User, contact: ActorRef[Response]) extends Event
  private case class ApplyTransaction(seller: User, bidder: User, amount: Int) extends Event

  // Responses
  sealed trait Response
  final case class RegistrationSuccessful(user: User) extends Response
  final case class RegistrationFailed(reason: String) extends Response
  final case class PaymentFailed(reason: String) extends Response
  case object PaymentSucceded extends Response
  final case class TransactionAckQuery(replyTo: ActorRef[Command]) extends Response

  // State
  private case class BankClient(user: User, balance: Int, contact: ActorRef[Response])

  private final case class BankState(accounts: Map[BankAccount, BankClient], counter: Int) {

    def generateNewAccountNumber() = this.copy(counter = counter + 1)

    def registerUser(user: User, contact: ActorRef[Response]) = 
        val newAccount = BankAccount(s"BE${counter}")     //Create new account
        val newUser = user.copy(bank = Some(newAccount))  //Give this account to the stored user
        val newClient = BankClient(newUser, 10, contact)  //Register a new user
        this.copy(accounts = accounts + (newAccount -> newClient), counter = counter + 1)

    def applyTransaction(seller: User, bidder: User, amount: Int) =
      val sellerKey = seller.bank.get

      val bidderAcc = this.accounts(bidder.bank.get)
      val updatedBidder = bidderAcc.copy(balance = bidderAcc.balance - amount)

      val sellerAcc = this.accounts(seller.bank.get)
      val updatedSeller= bidderAcc.copy(balance = sellerAcc.balance + amount)

      //FIX: Probably suboptimal way of changing elements of map but works
      val updatedAccounts = accounts.map {
        case (account, client) if account == seller.bank.get => account -> updatedSeller
        case (account, client) if account == bidder.bank.get => account -> updatedBidder
        case other => other
      }

      this.copy(accounts = updatedAccounts)

    def applyEvent(event: Event) = 
      event match {
        case UserRegistered(user, contact) => registerUser(user, contact)
        case ApplyTransaction(seller, bidder, amount) => applyTransaction(seller, bidder, amount)
      }


  }

  val Key: ServiceKey[Command] = ServiceKey("BankGateway")

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      val id = mkUUID()
      context.system.receptionist ! Receptionist.Register(Key, context.self)

      val commandHandler: (BankState, Command) => Effect[Event, BankState] = { (state, command) =>
        context.log.info(s"Processing ${command}")
        command match {
          case Ignore => Effect.none

          case RegisterUser(user, replyTo, contact) =>
            val event = UserRegistered(user, contact)
            Effect
              .persist(event)
              .thenRun { state =>

                 //FIX: Ugly hack to find newly added User
                 //Bank accounts are created in increasing order => can find newly added user by getting the maximum key
                 val newClient = state.accounts(state.accounts.keys.max(BankAccountOrdering))
                 replyTo ! RegistrationSuccessful(newClient.user)
              }

          /**
           * NOTE: 
            * Use a Business Handshake pattern. We will need to keep some state in the seller and bidder 
            * as they could accept certain bids and reject others => Set(ActorRef[], status)
            * amount to be debited and credited), 
            * BusinessHandshake is a short lived saga => its actor ref is unique
            */
          case PaymentRequest(seller, bidder, amount, replyTo) => {
            implicit val timeout: Timeout = 5.seconds
            val preCheck = for {
              extractBidder <- bidder.bank.toRight(PaymentFailed("Provided bidder has no bank Account"))
              bidderAccount <- state.accounts.get(extractBidder).toRight(PaymentFailed("Bidder is not registered to bank"))
              extractSeller <- if (bidderAccount.balance >= amount) seller.bank.toRight(PaymentFailed("Provided seller has no bank Account"))
                                else Left(PaymentFailed("Bidder has not enough balance"))
              sellerAccount <- state.accounts.get(extractSeller).toRight(PaymentFailed("Seller is not registered to bank"))
            } yield {
                val bankTranscation = context.spawnAnonymous(TransactionManager())
                context.ask(bankTranscation, (replyTo: ActorRef[TransactionManager.Response]) => TransactionManager.StartTransaction(sellerAccount.contact, bidderAccount.contact, replyTo)) {
                    case Success(value) => context.log.info(s"Transaction Manager Valid: ${value}"); TransactionSuccess(seller, bidder, amount, replyTo)
                    case Failure(exception) =>context.log.error(s"${exception}"); TransactionFailed(exception.getMessage(), replyTo)
                }
            }

            //As we use context.ask, positive response will be executed directly
            preCheck match {
              case Left(event) =>  replyTo ! event
              case Right(_) => //Do nothing, already exectued in Command
            }
            Effect.none
          }

          case TranscationAckResponse(_) => context.log.error("ERROR IS HERE"); ???

          case TransactionFailed(msg, replyTo) => replyTo ! PaymentFailed(msg); Effect.none

          case TransactionSuccess(seller, bidder, amount, replyTo) =>  {
            Effect.persist(ApplyTransaction(seller, bidder, amount)).thenReply(replyTo)(_ => PaymentSucceded)
          }
        }
      }

      def eventHandlerImpl(state: BankState, event: Event): BankState = {
          val updatedResult = state.applyEvent(event)
          context.log.info(s"Updated State: ${updatedResult}")
          updatedResult
      }

      EventSourcedBehavior[Command, Event, BankState](
        persistenceId = PersistenceId.ofUniqueId(id.toString()),
        emptyState = BankState(Map.empty, 0),
        commandHandler = commandHandler,
        eventHandler = (state, event) => eventHandlerImpl(state, event)
      )
    }
}
