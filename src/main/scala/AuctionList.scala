package eBayMicroServ

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors




object AuctionList:
  trait Command
  case class Get(auctions: Set[ActorRef[Auction.Command]], replyTo: ActorRef[eBay.Command]) extends Command

  trait Result
  trait ResultFragment extends Result
  case class ItemListing(item: String, price: Int, auctionRef: ActorRef[Auction.Command]) extends ResultFragment
  
  case class Answer(lst: List[ItemListing]) extends Result
  /**
  * CAREFUL: Because we use EventSourcing for eBay, it contains actorRefs to contact for each auction. 
  * However, once the system terminates completely (between 2 test runs), those actorRefs are destroyed.
  * However they still persits within eBay. Meaning that waitFor will be equal to the number of elements stored in eBay, from 
  * which certain actorsrefs do not exist. This will generate an infinte loop.
  * 
  */
  object Aggregator {


    private case object CollectionTimeout extends Result

    def apply(replyTo: ActorRef[eBay.Command], waitFor: Int) : Behavior[ResultFragment] = 
      Behaviors.setup { context => 

        //Item Listing represents how the item is passed by the auction
        //Displayable auctio is the format expected by eBay and sent back to the Bidder
        def active(lst: List[ItemListing], cntr: Int): Behavior[ResultFragment] = 
          if (cntr == 0)
            val res = lst.map(elt => DisplayableAuction(elt.item, elt.price, elt.auctionRef))
            context.log.info("Sent Reponse Back")
            replyTo ! eBay.AuctionListResult(res)
            Behaviors.stopped
          else 
            Behaviors.receive { (context, message) => 
              context.log.info(s"Processing ${message}")
              message match {
                case listing @ ItemListing(_, _, _) => active(listing :: lst, cntr - 1)
              }
            }

        active(List.empty, waitFor)
      }
  }


  //private case class Response(lst: List[DisplayableAuction]) extends Command

  def apply(): Behavior[Command] = 
    Behaviors.setup { context =>

      Behaviors.receive((context, message) => {
      context.log.info(s"Processing ${message}")
        message match {
          case Get(auctions: Set[ActorRef[Auction.Command]], replyTo) => {
            val aggregator = context.spawnAnonymous(Aggregator(replyTo, auctions.size))
            auctions.foreach(ref => ref ! Auction.GetMaxBid(aggregator))
            Behaviors.same
          }          
          // case Response(lst) => {
          //   lst.foreach(elt => context.log.info(s"- ${elt}"))
          //   Behaviors.stopped
          // }
        }
        // val aggregator = context.spawn

      })
    }

