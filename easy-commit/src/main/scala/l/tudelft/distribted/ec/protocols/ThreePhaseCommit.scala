package l.tudelft.distribted.ec.protocols

import io.vertx.core.AsyncResult
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.Json
import io.vertx.scala.core.Vertx
import io.vertx.scala.core.eventbus.Message
import l.tudelft.distribted.ec.HashMapDatabase
import l.tudelft.distribted.ec.protocols.NetworkState.NetworkState
import l.tudelft.distribted.ec.protocols.ProtocolState.ProtocolState
import l.tudelft.distribted.ec.protocols.{PrepareTransactionMessage,VoteCommitMessage,VoteAbortMessage,GlobalCommitMessage,GlobalAbortMessage,GlobalCommitAckMessage,GlobalAbortAckMessage}

import scala.collection.mutable

case class TransactionPreCommitRequest(sender: String, id: String, `type`: String="request.precommit") extends ProtocolMessage

case class TransactionPreCommitResponse(sender: String, id: String, `type`: String="response.precommit") extends ProtocolMessage

  class ThreePhaseCommit(
                      private val vertx: Vertx,
                      private val address: String,
                      private val database: HashMapDatabase,
                      private val timeout: Long = 5000L,
                      private val network: mutable.Map[String, NetworkState] = new mutable.HashMap[String, NetworkState]()
                    ) extends TwoPhaseCommit(vertx, address, database, timeout, network) {


    /**
     * Message has come in from one of the other cohorts.
     * This function decides which function should handle it.
     * Both supervisor- and supervised- targeted messages will come in,
     * meaning in some messages the agent should act as supervisor,
     * and in other messages as supervised.
     *
     * @param message raw message received.
     * @param protocolMessage parsed message, matched to one of its subclasses.
     */
    override def handleProtocolMessage(message: Message[Buffer], protocolMessage: ProtocolMessage): Unit = protocolMessage match {
      case TransactionPrepareRequest(sender, _, transaction, _) => handlePrepareRequest(message, sender, transaction)
      case TransactionAbortResponse(sender, id, _) => handleAbortResponse(message, sender, id)
      case TransactionCommitRequest(sender, id, _) => handleCommitRequest(message, sender, id)
      case TransactionPreCommitRequest(sender, id, _) => handlePreCommitRequest(message, sender, id)
    }

    /**
     * Handles the case that a READY package was sent to this agent.
     * Function only acts on this if this agent thinks it is the supervisor for this transaction.
     * If the number of READY's received from distinct senders
     * is equal to the number of agents in the network, this supervisor will commit.
     *
     * @param message raw form of the message that was sent. Used to directly reply.
     * @param sender the network address of the sender of this message.
     * @param id the id of the transaction the sender sent READY for.
     */
    override def handleReadyResponse(message: Message[Buffer], sender: String, id: String): Unit = {
      if (!stateManager.stateExists(id)) {
        // Probably supervised this earlier but was deleted due to an abort
        return
      }

      // Check if this agent is the supervising entity.
      // If not, drop this request and do not respond.
      if (stateManager.getSupervisor(id) != address) {
        return
      }

      stateManager.getState(id) match {
        case ReceivingReadyState(_, confirmedAddresses) =>
          confirmedAddresses += sender

          // TODO check if network.size is in- or excluding this node
          if (confirmedAddresses.size < network.size) {
            stateManager.updateState(id, ReceivingReadyState(id, confirmedAddresses))
            return
          }

          // Global decision has been made, transfer precommit decision to all cohorts
          preCommitTransaction(id)

        case _ =>
        // A READY was received by the supervisor, but the supervisor is not expecting it.
        // Drop the package.
        // TODO log that a wrong package was received
      }
    }

    /**
     * Handles the precommit procedure on the coordinator.
     * The decision is communicated to the cohorts expecting a reply.
     *
     * @param id the id of the transaction entering PRECOMMIT state.
     */
    def preCommitTransaction(id: String): Unit = {
      // In this case this node is the supervisor
      if (stateManager.stateExists(id)) {
        // TODO perhaps do something with the old transaction? Queue?
      }

      stateManager.updateState(id, ReceivingPrecommitState(id, mutable.HashSet()))
      sendToCohortExpectingReply(TransactionPreCommitRequest(address, id), reply => {
        if (reply.succeeded()) {
          reply.result().body().toJsonObject.mapTo(classOf[ProtocolMessage]) match {
            case TransactionPreCommitResponse(sender, id, _) => handlePreCommitResponse(reply.result(), sender, id)
            case _ => // TODO ABORT
          }
        } else {
          // TODO Time-out or other failure
        }
      })
    }

    /**
     * Handles the case that a PRECOMMIT package was sent to this agent.
     * Function only acts on this if this agent thinks it is the supervisor for this transaction.
     * If the number of PRECOMMITS's received from distinct senders
     * is equal to the number of agents in the network, this supervisor will commit.
     *
     * @param message raw form of the message that was sent. Used to directly reply.
     * @param sender the network address of the sender of this message.
     * @param id the id of the transaction the sender sent PRECOMMIT for.
     */
    def handlePreCommitResponse(message: Message[Buffer], sender: String, id: String): Unit = {
      if (!stateManager.stateExists(id)) {
        // Probably supervised this earlier but was deleted due to an abort
        return
      }

      // Check if this agent is the supervising entity.
      // If not, drop this request and do not respond.
      if (stateManager.getSupervisor(id) != address) {
        return
      }

      stateManager.getState(id) match {
        case ReceivingPrecommitState(_, confirmedAddresses) =>
          confirmedAddresses += sender

          if (confirmedAddresses.size < network.size) {
            stateManager.updateState(id, ReceivingPrecommitState(id, confirmedAddresses))
            return
          }

          // Global decision has been made, transfer commit decision to all cohorts
          commitTransaction(id)
        case _ =>
        // A READY was received by the supervisor, but the supervisor is not expecting it.
        // Drop the package.
        // TODO log that a wrong package was received

      }
    }

    /**
     * Handle precommitting of a transaction to the database.
     * If this message is from the supervisor, repeat the message to everyone
     * and update the database.
     *
     * @param message raw form of the message that was sent. Used to directly reply.
     * @param sender the sender of the commit request.
     * @param id the id of the transaction the commit was about.
     */
    def handlePreCommitRequest(message: Message[Buffer], sender: String, id: String): Unit = {
      if (!stateManager.stateExists(id)) {
        // No state with this id, so do not respond.
        //TODO log that a malicious package was found
        return
      }

      val state = stateManager.getState(id)
      if (state != ReadyState(id)) {
        // This state is not yet in the ready state.
        //TODO log that a malicious package was found.
        return
      }

      val supervisor = stateManager.getSupervisor(id)
      if (supervisor == sender && supervisor != address) {
        message.reply(TransactionPreCommitResponse(address, id))
      }
    }

  }