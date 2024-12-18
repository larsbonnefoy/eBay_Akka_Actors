package eBayMicroServ

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.NotUsed
import akka.actor.Terminated

case class User(name: String, bank: Option[BankAccount])

object eBayMainActor {
  val usr1 = User("Lars", None)
  // val usr2 = User("Lars2", None)

  def apply(): Behavior[NotUsed] = 
    Behaviors.setup { context =>
      val bankRef = context.spawn(BankManager(), "bankManager")
      val sellers = (1 to 10).map(pos => context.spawn(Seller(User(s"Seller${pos}", None), bankRef), s"Seller${pos}"))
      // val sellerRef = context.spawn(Seller(usr1, bankRef), "Seller1")

      Thread.sleep(3000)
      sellers.foreach(seller => seller ! CreateAuction("Test"))
      // sellers(0) ! CreateAuction("test")
      // sellers(0) ! CreateAuction("test")

      Behaviors.empty
    }
}

// @main
// def runManagerEbay() = ActorSystem(eBayMainActor(), "eBayMainActor")

object eBayMain extends App {
  def runManager() = ActorSystem(eBayMainActor(), "eBayMainActor")
  val system = runManager()
  system.terminate()
}