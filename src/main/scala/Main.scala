import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import HelloWorld.Greeted

object HelloWorld {
  final case class Greet(whom: String, replyTo: ActorRef[Greeted])
  final case class Greeted(whom: String, from: ActorRef[Greet])

  def apply(): Behavior[Greet] = Behaviors.receive {
    (context, message) => 

    context.log.info("Hello  {}!", message.whom)
    println(s"Hello ${message.whom}!")

    //replyTo is of type ActorRef[Greeted] => ! operator will only allow messages of type Greeted
    message.replyTo ! Greeted(message.whom, context.self)
    Behaviors.same
  }
}

object HelloWorldBot {

  def apply(max: Int): Behavior[HelloWorld.Greeted] = {
    bot(0, max)
  }

  private def bot(greetingCounter: Int, max: Int): Behavior[HelloWorld.Greeted] =
    //message here is a response from HelloWorld Actor => Greeted
    Behaviors.receive { (context, message) =>
      message match  { 
        case Greeted(whom, from) => {
          val n = greetingCounter + 1
          context.log.info("Greeting {} for {}", n, whom)
          println(s"Greeting $n for ${whom}")
          if (n == max) {
            Behaviors.stopped
          } else {
            from ! HelloWorld.Greet(whom, context.self) //message.from is source of message => ActorRef[Greet]
            bot(n, max)
          }
        }
      }
    }
}

object HelloWorldMain {

  final case class SayHello(name: String)

  def apply(): Behavior[SayHello] =
    Behaviors.setup { context =>
      //Greeter is executed only once i guess, to spawn HelloWorld Actor
      val greeter = context.spawn(HelloWorld(), "greeter")

      Behaviors.receiveMessage { message =>
        println(s"Hello World Main recv message ${message}")              //message is SayHello("World") or SayHello("Akka")
        val replyTo = context.spawn(HelloWorldBot(max = 3), message.name) //message.name is World or Akka
        greeter ! HelloWorld.Greet(message.name, replyTo)                 //send message.name and senderRef to greeter
        Behaviors.same
      }
    }
}

// object ActorBasics extends App {
//
//   val system: ActorSystem[HelloWorldMain.SayHello] = ActorSystem(HelloWorldMain(), "hello")
//
//   system ! HelloWorldMain.SayHello("World") // => 
//   system ! HelloWorldMain.SayHello("Akka")
//
// }
