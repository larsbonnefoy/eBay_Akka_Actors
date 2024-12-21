package eBayMicroServ

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors




object AuctionList:
  
  object Aggregator {
    trait Result
    case class ItemListing(item: String, price: Int, auctionRef: ActorRef[Auction.Command]) extends Result

    def apply(replyTo: ActorRef[AuctionList.Command], waitFor: Int) : Behavior[AuctionList.Aggregator.Result] = 
      Behaviors.setup { context => 

        def active(lst: List[ItemListing], cntr: Int): Behavior[Result] = 
          if (cntr == 0)
            val res = lst.map(elt => DisplayableAuction(elt.item, elt.price, elt.auctionRef))
            replyTo ! AuctionList.Response(res)
            Behaviors.stopped
          else 
            Behaviors.receive { (context, message) => 
              message match {
                case listing @ ItemListing(_, _, _) => active(listing :: lst, waitFor - 1)
              }
            }

        active(List.empty, waitFor)
      }


  }


  trait Command
  case class Get(auctions: Set[ActorRef[Auction.Command]], replyTo: ActorRef[eBay.Command]) extends Command
  private case class Response(lst: List[DisplayableAuction]) extends Command

  def apply(): Behavior[Command] = 
    Behaviors.setup { context =>
      context.log.info(s"Created AuctionList")

      Behaviors.receive((context, message) => {
      context.log.info(s"Processing ${message}")
        message match {
          case Get(auctions: Set[ActorRef[Auction.Command]], replyTo) => {
            val aggregator = context.spawnAnonymous(Aggregator(context.self, auctions.size))
            auctions.foreach(ref => ref ! Auction.GetMaxBid(aggregator))
            Behaviors.same
          }
          case Response(lst) => {
            lst.foreach(elt => context.log.info(s"- ${elt}"))
            Behaviors.stopped
          }
        }
        // val aggregator = context.spawn

      })
    }

