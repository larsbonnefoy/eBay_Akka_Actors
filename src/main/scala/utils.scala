package eBayMicroServ
import java.util.UUID

sealed trait StatusCode
object StatusCode {
  case object OK extends StatusCode
  case object Failed extends StatusCode
}

def mkUUID() = UUID.randomUUID() //Used from message correlations ids
