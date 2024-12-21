// package SellerShard
//
// import eBayMicroServ.User
// import eBayMicroServ.StatusCode
// import eBayMicroServ.mkUUID
// import akka.actor.typed.Behavior
// import akka.actor.typed.scaladsl.Behaviors
// import akka.persistence.typed.scaladsl.EventSourcedBehavior
// import akka.persistence.typed.PersistenceId
// import akka.persistence.typed.scaladsl.Effect
// import eBayMicroServ.AuthUser
// import akka.actor.typed.ActorRef
// import eBayMicroServ.BankAuth
// import eBayMicroServ.StartRegistration
// import akka.actor.typed.ActorSystem
// import eBayMicroServ.BankGateway
// import akka.NotUsed
// import akka.actor.typed.SupervisorStrategy
// import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
// import akka.cluster.sharding.typed.scaladsl.ClusterSharding
// import akka.cluster.sharding.typed.scaladsl.Entity
// import java.util.UUID
// import scala.util.Success
// import akka.actor.CoordinatedShutdown
//
// object Seller {
//   final case class SellerId(id: UUID)
//
//   sealed trait Command
//   final case class CreateSeller(newUser: User) extends Command
//   private final case class WrappedAuth(resp: AuthUser) extends Command
//
//   sealed trait Event
//   case class CreatedSeller(newUser: User, newId: SellerId) extends Event
//   case class AddedBank(userWithBank: User) extends Event
//
//   final private case class State(user: User, id: SellerId) {
//     def setBank(userWithBank: User) = this.copy(user = userWithBank)
//
//     def initSeller(newUser: User, newId: SellerId) =
//       this.copy(user = newUser, id = newId)
//
//     def applyEvent(event: Event) =
//       event match {
//         case CreatedSeller(usr, id) => initSeller(usr, id)
//         case AddedBank(usrWithBank) => setBank(usrWithBank)
//       }
//   }
//   
//   val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Seller")
//
//   def init(system: ActorSystem[?]): Unit = {
//     ClusterSharding(system).init(
//       Entity(TypeKey)(entityContext => 
//           //Build  SellerId from the provided entityId
//           Seller(SellerId(UUID.fromString(entityContext.entityId)))) 
//       )
//
//   }
//
//   def apply(sellerId: SellerId): Behavior[Command] =
//     Behaviors.setup { context =>
//       context.log.info("Created Seller")
//
//       val authMapper: ActorRef[AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))
//
//       def commandHandlerImpl(state: State, command: Command): Effect[Event, State] = {
//         command match {
//           case CreateSeller(newUser) => {
//             // context.ask(BankAsker(), (replyTo: ActorRef[AuthUser]) => StartRegistration(newUser, replyTo)) {
//             //   case Success(response) => ???
//             //   case Failure(ex) => ???
//             // }
//             val asker = context.spawnAnonymous(BankAuth())
//             asker ! StartRegistration(newUser, authMapper)
//             Effect.persist(CreatedSeller(newUser, sellerId))
//           }
//           case WrappedAuth(resp) => {
//             context.log.info(s"Got Account from Bank ${resp}")
//             Effect.persist(AddedBank(resp.user))
//           }
//         }
//       }
//
//       def eventHandlerImpl(state: State, event: Event): State = {
//           val updatedResult = state.applyEvent(event)
//           context.log.info("Current state of the data: {}", updatedResult)
//           updatedResult
//       }
//
//       import scala.concurrent.duration.DurationInt
//
//       EventSourcedBehavior[Command, Event, State] (
//         persistenceId = PersistenceId(TypeKey.name, sellerId.id.toString()),
//         emptyState = State(null.asInstanceOf[User], sellerId),
//         commandHandler = (state, cmd) => commandHandlerImpl(state, cmd),
//         eventHandler = (state, event) => eventHandlerImpl(state, event)
//       ).onPersistFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))
//     }
// }
//
// object SellerService:
//   def createSeller(system: ActorSystem[?]) = 
//     ClusterSharding(system).entityRefFor(Seller.TypeKey, mkUUID().toString())
//
//     
//
//
// object ShardSellerMainActor {
//   val usr1 = User("Lars")
//   // val usr2 = User("Lars2", None)
//
//   def apply(): Behavior[NotUsed] =
//     Behaviors.setup { context =>
//       Seller.init(context.system)
//
//       val bankRef = context.spawn(BankGateway(), "bankManager")
//       //
//       val seller = SellerService.createSeller(context.system)
//       seller ! CreateSeller(usr1)
//       // val sellers = (1 to 2)
//       //   .map(pos => context.spawnAnonymous(PersitentManager()))
//       //   .foreach(manager => manager ! InitSeller(usr1))
//
//
//       Behaviors.empty
//     }
// }
//
// object ShardSellerMain extends App {
//   def runManager() = ActorSystem(ShardSellerMainActor(), "ShardSeller")
//   val system = runManager()
//   Thread.sleep(5000)
//   // system.scheduler.scheduleOnce(1.minute) {
//   //   CoordinatedShutdown(system).run(CoordinatedShutdown.ClusterDowningReason.instance)
//   // }
// }
// //
// // final case class SellerId(id: UUID)
// //
// // private object Seller {
// //   def default: Seller = Seller(
// //     user = null.asInstanceOf[User],
// //     id = null.asInstanceOf[SellerId]
// //   )
// // }
// //
// // sealed trait Command extends ShardSellerMessages
// // case class InitSeller(newUser: User) extends Command
// // case class CreateAuction(startingPrice: Int, item: String) extends Command
// // case class SellerReply(code: StatusCode, msg: String) extends Command
// //
// // sealed trait Event extends ShardSellerMessages
// // case class CreatedSeller(newUser: User, newId: SellerId) extends Event
// // case class AddedBank(userWithBank: User) extends Event
// //
// // final case class Seller(user: User, id: SellerId):
// //   def setBank(userWithBank : User) = this.copy(user = userWithBank)
// //
// //   def initSeller(newUser: User, newId: SellerId) = this.copy(user = newUser, id = newId)
// //
// //   def applyEvent(event: Event) =
// //     event match {
// //       case CreatedSeller(usr, id) => initSeller(usr, id)
// //       case AddedBank(usrWithBank) => setBank(usrWithBank)
// //     }
// //
// // object PersitentManager:
// //
// //   private final case class WrappedAuth(resp: AuthUser) extends Command
// //
// //   val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Seller")
// //
// //   private val sellerUUID = mkUUID()
// //
// //   def init(system: ActorSystem[?]): Unit = {
// //     val sharding = ClusterSharding(system)
// //     sharding.init(Entity(TypeKey)(entityContext =>
// //        ))
// //
// //     // ClusterSharding(system).init(Entity(EntityKey)(entityContext =>
// //     //   Seller(entityContext.entityId)))
// //   }
// //
// //   def apply(sellerId: SellerId): Behavior[Command] =
// //     Behaviors.setup { context =>
// //       context.log.info("Created PeristentSellerManager")
// //
// //       val authMapper: ActorRef[AuthUser] = context.messageAdapter(rsp => WrappedAuth(rsp))
// //
// //       def commandHandlerImpl(state: Seller, command: Command): Effect[Event, Seller] = {
// //         command match {
// //           case InitSeller(newUser) => {
// //             val asker = context.spawnAnonymous(BankAsker())
// //             asker ! StartRegistration(newUser, authMapper)
// //             Effect.persist(CreatedSeller(newUser, SellerId(sellerUUID)))
// //           }
// //           case WrappedAuth(resp) => {
// //             context.log.info(s"Got Account from Bank ${resp}")
// //             Effect.persist(AddedBank(resp.user))
// //           }
// //         }
// //       }
// //
// //       def eventHandlerImpl(state: Seller, event: Event): Seller = {
// //           val updatedResult = state.applyEvent(event)
// //           context.log.info("Current state of the data: {}", updatedResult)
// //           updatedResult
// //       }
// //
// //       import scala.concurrent.duration.DurationInt
// //
// //       EventSourcedBehavior[Command, Event, Seller] (
// //         persistenceId = PersistenceId.ofUniqueId(sellerUUID.toString()),
// //         emptyState = Seller.default,
// //         commandHandler = (state, cmd) => commandHandlerImpl(state, cmd),
// //         eventHandler = (state, event) => eventHandlerImpl(state, event)
// //       ).onPersistFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))
// //     }
// //
// // object ShardSellerMainActor {
// //   val usr1 = User("Lars")
// //   // val usr2 = User("Lars2", None)
// //
// //   def apply(): Behavior[NotUsed] =
// //     Behaviors.setup { context =>
// //       val bankRef = context.spawn(BankGateway(), "bankManager")
// //       val sellers = (1 to 2)
// //         .map(pos => context.spawnAnonymous(PersitentManager()))
// //         .foreach(manager => manager ! InitSeller(usr1))
// //
// //       Thread.sleep(3000)
// //
// //       Behaviors.empty
// //     }
// // }
// //
// // object ShardSellerMain extends App {
// //   def runManager() = ActorSystem(ShardSellerMainActor(), "ShardSeller")
// //   val system = runManager()
// //   Seller.
// //   Thread.sleep(4000)
// //   system.terminate()
// // }
