package eBayMicroServ

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.NotUsed
import akka.actor.Terminated

case class User(name: String, bank: Option[BankAccount] = None)

object eBayMainActor {
  val usr1 = User("Lars")
  // val usr2 = User("Lars2", None)

  def apply(): Behavior[NotUsed] = 
    Behaviors.setup { context =>
      val bankRef = context.spawn(BankGateway(), "bankManager")
      val ebayRef = context.spawn(PersistentEbayManager(), "EbayManager")
      val sellers = (1 to 2).map(pos => context.spawnAnonymous(Seller(User(s"Seller${pos}"))))
      val bidders = (1 to 1).map(pos => context.spawnAnonymous(Bidder(User(s"Bidder${pos}"), ebayRef)))
      
      // val sellerRef = context.spawn(Seller(usr1, bankRef), "Seller1")

      Thread.sleep(3000)

      // sellers.foreach(seller => seller ! CreateAuction(10, "Test"))
      sellers(0) ! CreateAuction(100, "AnItem")
      sellers(1) ! CreateAuction(100, "AnItem")
      //sellers(1) ! CreateAuction(10, "AnotherItem")
      bidders(0) ! GetAuctions()

      Behaviors.empty
    }
}

// @main
// def runManagerEbay() = ActorSystem(eBayMainActor(), "eBayMainActor")

object eBayMain extends App {
  def runManager() = ActorSystem(eBayMainActor(), "eBayMainActor")
  val system = runManager()
  Thread.sleep(4000)
  system.terminate()
}
