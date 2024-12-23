package eBayMicroServ

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.NotUsed
import akka.actor.Terminated
import akka.event.Logging






case class User(name: String, bank: Option[BankAccount] = None)

object eBayMainActor {
  trait Protocol
  case class AuctionList(lst : List[DisplayableAuction]) extends Protocol

  val usr1 = User("Lars")
  // val usr2 = User("Lars2", None)

  def apply(): Behavior[Protocol] = 
    Behaviors.setup { context =>
      val bankRef = context.spawn(BankGateway(), "Bank")
      val ebayRef = context.spawn(eBay(), "eBay")

      val sellers = (1 to 2).map(pos => context.spawnAnonymous(Seller(User(s"Seller${pos}"))))
      val bidders = (1 to 2).map(pos => context.spawnAnonymous(Bidder(User(s"Bidder${pos}"), ebayRef)))

      Thread.sleep(2000)
      context.log.info("Creating Auctions")
      
      // sellers.foreach(seller => seller ! Seller.CreateAuction("Test", 10))
      sellers(0) ! Seller.CreateAuction("First", 1)
      sellers(1) ! Seller.CreateAuction("Second", 100)

      //Bidds need to be Created before we can call GetAuctions
      Thread.sleep(2000)

      // //sellers(1) ! CreateAuction(10, "AnotherItem")
      bidders(0) ! Bidder.GetAuctions(context.self)

      Behaviors.receive { (context, message) =>
        message match {
          case AuctionList(lst) => {
              lst.foreach(elt => context.log.info(s"- ${elt}"))
              bidders(0) ! Bidder.PlaceBid(10, lst(0).auctionRef)
              bidders(1) ! Bidder.PlaceBid(100, lst(0).auctionRef)
              //Thread.sleep(1000) //Wait a bit that bid is placed
              bidders(1) ! Bidder.RemoveBid(lst(0).auctionRef)
          }
          Behaviors.same
        }
      }

    }
}

// @main
// def runManagerEbay() = ActorSystem(eBayMainActor(), "eBayMainActor")

object eBayMain extends App {
  def runManager() = ActorSystem(eBayMainActor(), "eBayMainActor")
  val system = runManager()
  Thread.sleep(30000)
  system.terminate()
}
