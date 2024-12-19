package eBayMicroServ

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors


object AuctionListAggregator:
  def apply(replyTo: ActorRef )

case class GetAuctionList(replyTo: ActorRef[Any])

object AuctionList:
  def apply(aucRefs : Seq[ActorRef[AuctionCommand]]): Behavior[GetAuctionList] =
    Behaviors.receive((context, message) => {
      val aggregator = context.spawn

    })

