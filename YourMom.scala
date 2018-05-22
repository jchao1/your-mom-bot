package blazeit.yourmom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.io.Source
import scala.util.{Success, Failure}

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import slack.api.SlackApiClient
import slack.models.{User, Im, Message}
import slack.rtm.SlackRtmClient

object YourMom {
  implicit val system = ActorSystem("slack")
  val challengeTimeout = 2 minutes
  var nextReset: Option[Cancellable] = None

  def main(args: Array[String]) = {
    refreshAndRun()
  }

  def refreshAndRun(): Unit = {
    val token = Source.fromFile("token.txt").mkString.replace("\n", "")
    val apiClient = SlackApiClient(token)
    val resolvedSlackInfo: Future[(Seq[User], Seq[Im])] = for {
      users <- apiClient.listUsers()
      ims <- apiClient.listIms()
    } yield (users, ims)

    resolvedSlackInfo.onComplete {
      case Success((users, ims)) => {
        val usersMap = users.map { user =>
          (user.id, user)
        }.toMap
        val channelIdsMap = (for {
          imChannel <- ims
          user <- usersMap.get(imChannel.user)
        } yield (imChannel.id, user)).toMap
        listenForYourMom(apiClient, SlackRtmClient(token), usersMap, channelIdsMap)
      }
      case Failure(err) => {
        println("[LOG] startup failed")
        println(err)
      }
    }
  }

  /**
    * We want the bot to time out if expected input is not received after a period of time. This method will clear any existing timeout (if exists),
    * and schedule another timeout with the anonymous function passed to it. This function will be executed after a period of time, if updateReset is
    * not called again.
    */
  def updateReset(onUpdate: () => Unit) = {
    nextReset.map(_.cancel())
    nextReset = Some(system.scheduler.scheduleOnce(challengeTimeout) {
      onUpdate()
    })
  }

  /**
    * Given an rtmClient and a message received with this client, check for command strings, and respond as necessary.
    * Options:
    * - restart: close the rtmClient and start the bot anew
    * - status: print the current status of the bot, as passed to this method
    * - help: print out info about the bot
    */
  def checkForCommands(rtmClient: SlackRtmClient, message: Message, status: String, challengingUserOpt: Option[User], challengedUserOpt: Option[User]): Boolean = {
    val sendMessage = (msg: String) => rtmClient.sendMessage(message.channel, msg)
    val messageContains = (substring: String) => message.text.toLowerCase().contains(substring): Boolean

    if (!message.text.contains(s"<@${rtmClient.state.self.id}>")) {
      return false
    }

    // Restart bot
    if (messageContains("restart")) {
      sendMessage("Restarting...")
      rtmClient.close()
      refreshAndRun()
      return true
    }
    return false
  }

  /**
    * Start a listenern using rtmClient which will listen for any "odds are" challenges. This should be running whenever a challenge has not been initiated.
    */
  def listenForYourMom(apiClient: SlackApiClient, rtmClient: SlackRtmClient, uidsToUsers: Map[String, User], channelIdToUsers: Map[String, User]): ActorRef = {
    val usernamesToUsers = uidsToUsers.map { case (id, user) =>
      (user.name, user)
    }.toMap
    val usernameRegex = uidsToUsers.values.map(_.name).mkString("|").r
    val userIdRegex = uidsToUsers.keys.mkString("|").r

    lazy val listener: ActorRef = rtmClient.onMessage { message =>
      val sendMessage = (msg: String) => rtmClient.sendMessage(message.channel, msg)
      val messageContains = (substring: String) => message.text.toLowerCase().contains(substring): Boolean

      if (message.user != rtmClient.state.self.id && !checkForCommands(rtmClient, message, "listening for challenges", None, None) && messageContains("ur mom")) {
        apiClient.kickFromGroup(message.channel, message.user)

      }
    }
    listener
  }

}
