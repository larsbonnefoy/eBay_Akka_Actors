package eBayMicroServ
package eBayMicroServ
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import scala.collection.immutable.TreeSet
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect


final case class AuctionListing(seller: SellerRef, auction: AuctionRef, replyTo: ActorRef[AuctionCommand])

trait ebayCommand
case class RegisterAuction(seller: SellerRef, auction: AuctionRef, replyTo: ActorRef[AuctionCommand])

trait ebayEvent
case class RegisteredAuction(seller: SellerRef , auction: AuctionRef, replyTo: ActorRef[AuctionCommand])

case class eBay(auctions: TreeSet[AuctionListing])
