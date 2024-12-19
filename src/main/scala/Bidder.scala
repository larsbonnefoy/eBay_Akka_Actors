package eBayMicroServ
import java.util.UUID

final case class BidderRef(id: UUID)
final case class Bidder(user: User, sellerId: BidderRef)

sealed trait BidderAction
case class MakeBid() extends BidderAction
case class WithdrawBid() extends BidderAction
case class getAuctions() extends BidderAction

// object Bidder:
