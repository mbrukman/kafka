/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.controller

import kafka.api.{LeaderAndIsr, KAFKA_083, PartitionStateInfo}
import kafka.utils._
import org.apache.kafka.clients.{ClientResponse, ClientRequest, ManualMetadataUpdater, NetworkClient}
import org.apache.kafka.common.{TopicPartition, Node}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network.{Selectable, ChannelBuilders, Selector, NetworkReceive}
import org.apache.kafka.common.protocol.{SecurityProtocol, ApiKeys}
import org.apache.kafka.common.requests._
import org.apache.kafka.common.security.ssl.SSLFactory
import org.apache.kafka.common.utils.Time
import collection.mutable.HashMap
import kafka.cluster.Broker
import java.net.{SocketTimeoutException}
import java.util.concurrent.{LinkedBlockingQueue, BlockingQueue}
import kafka.server.KafkaConfig
import collection.mutable
import kafka.common.{KafkaException, TopicAndPartition}
import collection.Set
import collection.JavaConverters._

class ControllerChannelManager(controllerContext: ControllerContext, config: KafkaConfig, time: Time, metrics: Metrics) extends Logging {
  protected val brokerStateInfo = new HashMap[Int, ControllerBrokerStateInfo]
  private val brokerLock = new Object
  this.logIdent = "[Channel manager on controller " + config.brokerId + "]: "

  controllerContext.liveBrokers.foreach(addNewBroker(_))

  def startup() = {
    brokerLock synchronized {
      brokerStateInfo.foreach(brokerState => startRequestSendThread(brokerState._1))
    }
  }

  def shutdown() = {
    brokerLock synchronized {
      brokerStateInfo.values.foreach(removeExistingBroker)
    }
  }

  def sendRequest(brokerId: Int, apiKey: ApiKeys, apiVersion: Option[Short], request: AbstractRequest, callback: AbstractRequestResponse => Unit = null) {
    brokerLock synchronized {
      val stateInfoOpt = brokerStateInfo.get(brokerId)
      stateInfoOpt match {
        case Some(stateInfo) =>
          stateInfo.messageQueue.put(QueueItem(apiKey, apiVersion, request, callback))
        case None =>
          warn("Not sending request %s to broker %d, since it is offline.".format(request, brokerId))
      }
    }
  }

  def addBroker(broker: Broker) {
    // be careful here. Maybe the startup() API has already started the request send thread
    brokerLock synchronized {
      if(!brokerStateInfo.contains(broker.id)) {
        addNewBroker(broker)
        startRequestSendThread(broker.id)
      }
    }
  }

  def removeBroker(brokerId: Int) {
    brokerLock synchronized {
      removeExistingBroker(brokerStateInfo(brokerId))
    }
  }

  private def addNewBroker(broker: Broker) {
    val messageQueue = new LinkedBlockingQueue[QueueItem]
    debug("Controller %d trying to connect to broker %d".format(config.brokerId, broker.id))
    val brokerEndPoint = broker.getBrokerEndPoint(config.interBrokerSecurityProtocol)
    val brokerNode = new Node(broker.id, brokerEndPoint.host, brokerEndPoint.port)
    val networkClient = {
      val selector = new Selector(
        NetworkReceive.UNLIMITED,
        config.connectionsMaxIdleMs,
        metrics,
        time,
        "controller-channel",
        Map("broker-id" -> broker.id.toString).asJava,
        false,
        ChannelBuilders.create(config.interBrokerSecurityProtocol, SSLFactory.Mode.CLIENT, config.channelConfigs)
      )
      new NetworkClient(
        selector,
        new ManualMetadataUpdater(Seq(brokerNode).asJava),
        config.brokerId.toString,
        1,
        0,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        Selectable.USE_DEFAULT_BUFFER_SIZE
      )
    }
    val requestThread = new RequestSendThread(config.brokerId, controllerContext, broker, messageQueue, networkClient, brokerNode, config, time)
    requestThread.setDaemon(false)
    brokerStateInfo.put(broker.id, new ControllerBrokerStateInfo(networkClient, brokerNode, broker, messageQueue, requestThread))
  }

  private def removeExistingBroker(brokerState: ControllerBrokerStateInfo) {
    try {
      brokerState.networkClient.close()
      brokerState.messageQueue.clear()
      brokerState.requestSendThread.shutdown()
      brokerStateInfo.remove(brokerState.broker.id)
    } catch {
      case e: Throwable => error("Error while removing broker by the controller", e)
    }
  }

  protected def startRequestSendThread(brokerId: Int) {
    val requestThread = brokerStateInfo(brokerId).requestSendThread
    if(requestThread.getState == Thread.State.NEW)
      requestThread.start()
  }
}

case class QueueItem(apiKey: ApiKeys, apiVersion: Option[Short], request: AbstractRequest, callback: AbstractRequestResponse => Unit)

class RequestSendThread(val controllerId: Int,
                        val controllerContext: ControllerContext,
                        val toBroker: Broker,
                        val queue: BlockingQueue[QueueItem],
                        val networkClient: NetworkClient,
                        val brokerNode: Node,
                        val config: KafkaConfig,
                        val time: Time)
  extends ShutdownableThread("Controller-%d-to-broker-%d-send-thread".format(controllerId, toBroker.id)) {

  private val lock = new Object()
  private val stateChangeLogger = KafkaController.stateChangeLogger
  private val socketTimeoutMs = config.controllerSocketTimeoutMs

  override def doWork(): Unit = {

    def backoff(): Unit = CoreUtils.swallowTrace(Thread.sleep(300))

    val QueueItem(apiKey, apiVersion, request, callback) = queue.take()
    import NetworkClientBlockingOps._
    var clientResponse: ClientResponse = null
    try {
      lock synchronized {
        var isSendSuccessful = false
        while (isRunning.get() && !isSendSuccessful) {
          // if a broker goes down for a long time, then at some point the controller's zookeeper listener will trigger a
          // removeBroker which will invoke shutdown() on this thread. At that point, we will stop retrying.
          try {
            if (!brokerReady()) {
              isSendSuccessful = false
              backoff()
            }
            else {
              val requestHeader = apiVersion.fold(networkClient.nextRequestHeader(apiKey))(networkClient.nextRequestHeader(apiKey, _))
              val send = new RequestSend(brokerNode.idString, requestHeader, request.toStruct)
              val clientRequest = new ClientRequest(time.milliseconds(), true, send, null)
              clientResponse = networkClient.blockingSendAndReceive(clientRequest, socketTimeoutMs)(time).getOrElse {
                throw new SocketTimeoutException(s"No response received within $socketTimeoutMs ms")
              }
              isSendSuccessful = true
            }
          } catch {
            case e: Throwable => // if the send was not successful, reconnect to broker and resend the message
              warn(("Controller %d epoch %d fails to send request %s to broker %s. " +
                "Reconnecting to broker.").format(controllerId, controllerContext.epoch,
                  request.toString, toBroker.toString()), e)
              networkClient.close(brokerNode.idString)
              isSendSuccessful = false
              backoff()
          }
        }
        if (clientResponse != null) {
          val response = ApiKeys.forId(clientResponse.request.request.header.apiKey) match {
            case ApiKeys.LEADER_AND_ISR => new LeaderAndIsrResponse(clientResponse.responseBody)
            case ApiKeys.STOP_REPLICA => new StopReplicaResponse(clientResponse.responseBody)
            case ApiKeys.UPDATE_METADATA_KEY => new UpdateMetadataResponse(clientResponse.responseBody)
            case apiKey => throw new KafkaException(s"Unexpected apiKey received: $apiKey")
          }
          stateChangeLogger.trace("Controller %d epoch %d received response %s for a request sent to broker %s"
            .format(controllerId, controllerContext.epoch, response.toString, toBroker.toString))

          if (callback != null) {
            callback(response)
          }
        }
      }
    } catch {
      case e: Throwable =>
        error("Controller %d fails to send a request to broker %s".format(controllerId, toBroker.toString()), e)
        // If there is any socket error (eg, socket timeout), the connection is no longer usable and needs to be recreated.
        networkClient.close(brokerNode.idString)
    }
  }

  private def brokerReady(): Boolean = {
    import NetworkClientBlockingOps._
    try {

      if (networkClient.isReady(brokerNode, time.milliseconds()))
        true
      else {
        val ready = networkClient.blockingReady(brokerNode, socketTimeoutMs)(time)

        if (!ready)
          throw new SocketTimeoutException(s"Failed to connect within $socketTimeoutMs ms")

        info("Controller %d connected to %s for sending state change requests".format(controllerId, toBroker.toString()))
        true
      }
    } catch {
      case e: Throwable =>
        error("Controller %d's connection to broker %s was unsuccessful".format(controllerId, toBroker.toString()), e)
        networkClient.close(brokerNode.idString)
        false
    }
  }

}

class ControllerBrokerRequestBatch(controller: KafkaController) extends  Logging {
  val controllerContext = controller.controllerContext
  val controllerId: Int = controller.config.brokerId
  val leaderAndIsrRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, PartitionStateInfo]]
  val stopReplicaRequestMap = mutable.Map.empty[Int, Seq[StopReplicaRequestInfo]]
  val updateMetadataRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, PartitionStateInfo]]
  private val stateChangeLogger = KafkaController.stateChangeLogger

  def newBatch() {
    // raise error if the previous batch is not empty
    if (leaderAndIsrRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating " +
        "a new one. Some LeaderAndIsr state changes %s might be lost ".format(leaderAndIsrRequestMap.toString()))
    if (stopReplicaRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        "new one. Some StopReplica state changes %s might be lost ".format(stopReplicaRequestMap.toString()))
    if (updateMetadataRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        "new one. Some UpdateMetadata state changes %s might be lost ".format(updateMetadataRequestMap.toString()))
  }

  def clear() {
    leaderAndIsrRequestMap.clear()
    stopReplicaRequestMap.clear()
    updateMetadataRequestMap.clear()
  }

  def addLeaderAndIsrRequestForBrokers(brokerIds: Seq[Int], topic: String, partition: Int,
                                       leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch,
                                       replicas: Seq[Int], callback: AbstractRequestResponse => Unit = null) {
    val topicPartition = new TopicPartition(topic, partition)

    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val result = leaderAndIsrRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty)
      result.put(topicPartition, PartitionStateInfo(leaderIsrAndControllerEpoch, replicas.toSet))
    }

    addUpdateMetadataRequestForBrokers(controllerContext.liveOrShuttingDownBrokerIds.toSeq,
                                       Set(TopicAndPartition(topic, partition)))
  }

  def addStopReplicaRequestForBrokers(brokerIds: Seq[Int], topic: String, partition: Int, deletePartition: Boolean,
                                      callback: (AbstractRequestResponse, Int) => Unit = null) {
    brokerIds.filter(b => b >= 0).foreach { brokerId =>
      stopReplicaRequestMap.getOrElseUpdate(brokerId, Seq.empty[StopReplicaRequestInfo])
      val v = stopReplicaRequestMap(brokerId)
      if(callback != null)
        stopReplicaRequestMap(brokerId) = v :+ StopReplicaRequestInfo(PartitionAndReplica(topic, partition, brokerId),
          deletePartition, (r: AbstractRequestResponse) => callback(r, brokerId))
      else
        stopReplicaRequestMap(brokerId) = v :+ StopReplicaRequestInfo(PartitionAndReplica(topic, partition, brokerId),
          deletePartition)
    }
  }

  /** Send UpdateMetadataRequest to the given brokers for the given partitions and partitions that are being deleted */
  def addUpdateMetadataRequestForBrokers(brokerIds: Seq[Int],
                                         partitions: collection.Set[TopicAndPartition] = Set.empty[TopicAndPartition],
                                         callback: AbstractRequestResponse => Unit = null) {
    def updateMetadataRequestMapFor(partition: TopicAndPartition, beingDeleted: Boolean) {
      val leaderIsrAndControllerEpochOpt = controllerContext.partitionLeadershipInfo.get(partition)
      leaderIsrAndControllerEpochOpt match {
        case Some(leaderIsrAndControllerEpoch) =>
          val replicas = controllerContext.partitionReplicaAssignment(partition).toSet
          val partitionStateInfo = if (beingDeleted) {
            val leaderAndIsr = new LeaderAndIsr(LeaderAndIsr.LeaderDuringDelete, leaderIsrAndControllerEpoch.leaderAndIsr.isr)
            PartitionStateInfo(LeaderIsrAndControllerEpoch(leaderAndIsr, leaderIsrAndControllerEpoch.controllerEpoch), replicas)
          } else {
            PartitionStateInfo(leaderIsrAndControllerEpoch, replicas)
          }
          brokerIds.filter(b => b >= 0).foreach { brokerId =>
            updateMetadataRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty[TopicPartition, PartitionStateInfo])
            updateMetadataRequestMap(brokerId).put(new TopicPartition(partition.topic, partition.partition), partitionStateInfo)
          }
        case None =>
          info("Leader not yet assigned for partition %s. Skip sending UpdateMetadataRequest.".format(partition))
      }
    }

    val filteredPartitions = {
      val givenPartitions = if (partitions.isEmpty)
        controllerContext.partitionLeadershipInfo.keySet
      else
        partitions
      if (controller.deleteTopicManager.partitionsToBeDeleted.isEmpty)
        givenPartitions
      else
        givenPartitions -- controller.deleteTopicManager.partitionsToBeDeleted
    }
    if (filteredPartitions.isEmpty)
      brokerIds.filter(b => b >= 0).foreach { brokerId =>
        updateMetadataRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty[TopicPartition, PartitionStateInfo])
      }
    else
      filteredPartitions.foreach(partition => updateMetadataRequestMapFor(partition, beingDeleted = false))

    controller.deleteTopicManager.partitionsToBeDeleted.foreach(partition => updateMetadataRequestMapFor(partition, beingDeleted = true))
  }

  def sendRequestsToBrokers(controllerEpoch: Int) {
    try {
      leaderAndIsrRequestMap.foreach { case (broker, partitionStateInfos) =>
        partitionStateInfos.foreach { case (topicPartition, state) =>
          val typeOfRequest = if (broker == state.leaderIsrAndControllerEpoch.leaderAndIsr.leader) "become-leader" else "become-follower"
          stateChangeLogger.trace(("Controller %d epoch %d sending %s LeaderAndIsr request %s to broker %d " +
                                   "for partition [%s,%d]").format(controllerId, controllerEpoch, typeOfRequest,
                                                                   state.leaderIsrAndControllerEpoch, broker,
                                                                   topicPartition.topic, topicPartition.partition))
        }
        val leaderIds = partitionStateInfos.map(_._2.leaderIsrAndControllerEpoch.leaderAndIsr.leader).toSet
        val leaders = controllerContext.liveOrShuttingDownBrokers.filter(b => leaderIds.contains(b.id)).map { b =>
          val brokerEndPoint = b.getBrokerEndPoint(controller.config.interBrokerSecurityProtocol)
          new LeaderAndIsrRequest.EndPoint(brokerEndPoint.id, brokerEndPoint.host, brokerEndPoint.port)
        }
        val partitionStates = partitionStateInfos.map { case (topicPartition, partitionStateInfo) =>
          val LeaderIsrAndControllerEpoch(leaderIsr, controllerEpoch) = partitionStateInfo.leaderIsrAndControllerEpoch
          val partitionState = new LeaderAndIsrRequest.PartitionState(controllerEpoch, leaderIsr.leader,
            leaderIsr.leaderEpoch, leaderIsr.isr.map(Integer.valueOf).asJava, leaderIsr.zkVersion,
            partitionStateInfo.allReplicas.map(Integer.valueOf).asJava
          )
          topicPartition -> partitionState
        }
        val leaderAndIsrRequest = new LeaderAndIsrRequest(controllerId, controllerEpoch, partitionStates.asJava, leaders.asJava)
        controller.sendRequest(broker, ApiKeys.LEADER_AND_ISR, None, leaderAndIsrRequest, null)
      }
      leaderAndIsrRequestMap.clear()
      updateMetadataRequestMap.foreach { case (broker, partitionStateInfos) =>

        partitionStateInfos.foreach(p => stateChangeLogger.trace(("Controller %d epoch %d sending UpdateMetadata request %s " +
          "to broker %d for partition %s").format(controllerId, controllerEpoch, p._2.leaderIsrAndControllerEpoch,
          broker, p._1)))
        val partitionStates = partitionStateInfos.map { case (topicPartition, partitionStateInfo) =>
          val LeaderIsrAndControllerEpoch(leaderIsr, controllerEpoch) = partitionStateInfo.leaderIsrAndControllerEpoch
          val partitionState = new UpdateMetadataRequest.PartitionState(controllerEpoch, leaderIsr.leader,
            leaderIsr.leaderEpoch, leaderIsr.isr.map(Integer.valueOf).asJava, leaderIsr.zkVersion,
            partitionStateInfo.allReplicas.map(Integer.valueOf).asJava
          )
          topicPartition -> partitionState
        }

        val version = if (controller.config.interBrokerProtocolVersion.onOrAfter(KAFKA_083)) (1: Short) else (0: Short)

        val updateMetadataRequest =
          if (version == 0) {
            val liveBrokers = controllerContext.liveOrShuttingDownBrokers.map { broker =>
              val brokerEndPoint = broker.getBrokerEndPoint(SecurityProtocol.PLAINTEXT)
              new UpdateMetadataRequest.BrokerEndPoint(brokerEndPoint.id, brokerEndPoint.host, brokerEndPoint.port)
            }
            new UpdateMetadataRequest(controllerId, controllerEpoch, liveBrokers.asJava, partitionStates.asJava)
          }
          else {
            val liveBrokers = controllerContext.liveOrShuttingDownBrokers.map { broker =>
              val endPoints = broker.endPoints.map { case (securityProtocol, endPoint) =>
                securityProtocol -> new UpdateMetadataRequest.EndPoint(endPoint.host, endPoint.port)
              }
              new UpdateMetadataRequest.Broker(broker.id, endPoints.asJava)
            }
            new UpdateMetadataRequest(controllerId, controllerEpoch, partitionStates.asJava, liveBrokers.asJava)
          }

        controller.sendRequest(broker, ApiKeys.UPDATE_METADATA_KEY, Some(version), updateMetadataRequest, null)
      }
      updateMetadataRequestMap.clear()
      stopReplicaRequestMap.foreach { case (broker, replicaInfoList) =>
        val stopReplicaWithDelete = replicaInfoList.filter(_.deletePartition).map(_.replica).toSet
        val stopReplicaWithoutDelete = replicaInfoList.filterNot(_.deletePartition).map(_.replica).toSet
        debug("The stop replica request (delete = true) sent to broker %d is %s"
          .format(broker, stopReplicaWithDelete.mkString(",")))
        debug("The stop replica request (delete = false) sent to broker %d is %s"
          .format(broker, stopReplicaWithoutDelete.mkString(",")))
        replicaInfoList.foreach { r =>
          val stopReplicaRequest = new StopReplicaRequest(controllerId, controllerEpoch, r.deletePartition,
            Set(new TopicPartition(r.replica.topic, r.replica.partition)).asJava)
          controller.sendRequest(broker, ApiKeys.STOP_REPLICA, None, stopReplicaRequest, r.callback)
        }
      }
      stopReplicaRequestMap.clear()
    } catch {
      case e : Throwable => {
        if (leaderAndIsrRequestMap.size > 0) {
          error("Haven't been able to send leader and isr requests, current state of " +
              s"the map is $leaderAndIsrRequestMap")
        }
        if (updateMetadataRequestMap.size > 0) {
          error("Haven't been able to send metadata update requests, current state of " +
              s"the map is $updateMetadataRequestMap")
        }
        if (stopReplicaRequestMap.size > 0) {
          error("Haven't been able to send stop replica requests, current state of " +
              s"the map is $stopReplicaRequestMap")
        }
        throw new IllegalStateException(e)
      }
    }
  }
}

case class ControllerBrokerStateInfo(networkClient: NetworkClient,
                                     brokerNode: Node,
                                     broker: Broker,
                                     messageQueue: BlockingQueue[QueueItem],
                                     requestSendThread: RequestSendThread)

case class StopReplicaRequestInfo(replica: PartitionAndReplica, deletePartition: Boolean, callback: AbstractRequestResponse => Unit = null)

class Callbacks private (var leaderAndIsrResponseCallback: AbstractRequestResponse => Unit = null,
                         var updateMetadataResponseCallback: AbstractRequestResponse => Unit = null,
                         var stopReplicaResponseCallback: (AbstractRequestResponse, Int) => Unit = null)

object Callbacks {
  class CallbackBuilder {
    var leaderAndIsrResponseCbk: AbstractRequestResponse => Unit = null
    var updateMetadataResponseCbk: AbstractRequestResponse => Unit = null
    var stopReplicaResponseCbk: (AbstractRequestResponse, Int) => Unit = null

    def leaderAndIsrCallback(cbk: AbstractRequestResponse => Unit): CallbackBuilder = {
      leaderAndIsrResponseCbk = cbk
      this
    }

    def updateMetadataCallback(cbk: AbstractRequestResponse => Unit): CallbackBuilder = {
      updateMetadataResponseCbk = cbk
      this
    }

    def stopReplicaCallback(cbk: (AbstractRequestResponse, Int) => Unit): CallbackBuilder = {
      stopReplicaResponseCbk = cbk
      this
    }

    def build: Callbacks = {
      new Callbacks(leaderAndIsrResponseCbk, updateMetadataResponseCbk, stopReplicaResponseCbk)
    }
  }
}
