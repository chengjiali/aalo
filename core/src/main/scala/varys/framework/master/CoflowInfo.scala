package varys.framework.master

import akka.actor.ActorRef

import java.util.Date
import java.util.concurrent.atomic._
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, HashMap}

import varys.framework.{FlowDescription, CoflowDescription}

private[varys] class CoflowInfo(
    val startTime: Long,
    val id: String,
    val desc: CoflowDescription,
    val submitDate: Date,
    val actor: ActorRef) {
  
  var state = CoflowState.WAITING
  var alpha = 0.0
  
  var bytesLeft_ = new AtomicLong(0L)
  def bytesLeft: Long = bytesLeft_.get()
  
  var readyTime = -1L
  var endTime = -1L

  // Data structure to keep track of total allocation of this coflow over time. Initialized with 0.0
  var allocationOverTime = new ArrayBuffer[(Long, Double)]()
  allocationOverTime += ((startTime, 0.0))
  
  private val idToFlow = new ConcurrentHashMap[String, FlowInfo]()

  private var _retryCount = 0

  def retryCount = _retryCount

  private val numRegisteredFlows = new AtomicInteger(0)
  private val numCompletedFlows = new AtomicInteger(0)

  def numFlowsToRegister = desc.maxFlows - numRegisteredFlows.get
  def numFlowsToComplete = desc.maxFlows - numCompletedFlows.get
  
  def getFlows() = idToFlow.values.asScala.filter(_.isLive)

  def getFlowInfos(flowIds: Array[String]): Option[Array[FlowInfo]] = {
    val ret = flowIds.map(idToFlow.get(_))
    ret.foreach(v => {
      if (v == null)
        return None
    })
    Some(ret)
  }

  def getFlowInfo(flowId: String): Option[FlowInfo] = {
    val ret = idToFlow.get(flowId)
    if (ret == null) None else Some(ret)
  }

  def contains(flowId: String) = idToFlow.containsKey(flowId)

  /**
   * Calculating static alpha for the coflow
   */
  private def calcAlpha(): Double = {
    val sBytes = new HashMap[String, Double]().withDefaultValue(0.0)
    val rBytes = new HashMap[String, Double]().withDefaultValue(0.0)

    getFlows.foreach { flowInfo =>
      // FIXME: Assuming a single source and destination for each flow
      val src = flowInfo.source
      val dst = flowInfo.destClient.host
      
      sBytes(src) = sBytes(src) + flowInfo.desc.sizeInBytes
      rBytes(dst) = rBytes(dst) + flowInfo.desc.sizeInBytes
    }
    math.max(sBytes.values.max, rBytes.values.max)
  }

  def addFlow(flowDesc: FlowDescription) {
    assert(!idToFlow.containsKey(flowDesc.id))
    idToFlow.put(flowDesc.id, new FlowInfo(flowDesc))
    bytesLeft_.getAndAdd(flowDesc.sizeInBytes)
  }

  /**
   * Adds destination for a given piece of data. 
   * Assume flowId already exists in idToFlow
   * Returns true if the coflow is ready to go
   */
  def addDestination(flowId: String, destClient: ClientInfo): Boolean = {
    if (idToFlow.get(flowId).destClient == null) {
      numRegisteredFlows.getAndIncrement
    }
    idToFlow.get(flowId).setDestination(destClient)
    postProcessIfReady
  }

  /**
   * Mark this coflow as RUNNING only after all flows are alive
   * Returns true if the coflow is ready to go
   */
  private def postProcessIfReady(): Boolean = {
    if (numRegisteredFlows.get == desc.maxFlows) {
      state = CoflowState.READY
      readyTime = System.currentTimeMillis
      alpha = calcAlpha()
      true
    } else {
      false
    }
  }

  /**
   * Keep track of allocation over time
   */
  def setCurrentAllocation(newTotalBps: Double) = {
    allocationOverTime += ((System.currentTimeMillis, newTotalBps))
  }

  def currentAllocation(): (Long, Double) = allocationOverTime.last

  /**
   * Adds destinations for a multiple pieces of data. 
   * Assume flowId already exists in idToFlow
   * Returns true if the coflow is ready to go
   */
  def addDestinations(flowIds: Array[String], destClient: ClientInfo): Boolean = {
    flowIds.foreach { flowId => 
      if (idToFlow.get(flowId).destClient == null) {
        numRegisteredFlows.getAndIncrement
      }
      idToFlow.get(flowId).setDestination(destClient)
    }
    postProcessIfReady
  }

  /**
   * Updates bytes remaining in the specified flow
   * Returns true if the flow has completed; false otherwise
   */
  def updateFlow(flowId: String, bytesSinceLastUpdate: Long, isCompleted: Boolean): Boolean = {
    val flow = idToFlow.get(flowId)
    flow.decreaseBytes(bytesSinceLastUpdate)
    bytesLeft_.getAndAdd(-bytesSinceLastUpdate)
    if (isCompleted) {
      assert(flow.bytesLeft == 0)
      numCompletedFlows.getAndIncrement
      true
    }
    false
  }

  def removeFlow(flowId: String) {
    // TODO: 
  }

  def incrementRetryCount = {
    _retryCount += 1
    _retryCount
  }

  def markFinished(endState: CoflowState.Value) {
    state = endState
    endTime = System.currentTimeMillis()
  }

  /**
   * Returns an estimation of remaining bytes
   */
  def remainingSizeInBytes(): Double = {
    bytesLeft.toDouble
  }

  def duration: Long = {
    if (endTime != -1) {
      endTime - startTime
    } else {
      System.currentTimeMillis() - startTime
    }
  }
  
  override def toString: String = "CoflowInfo(" + id + "[" + desc + "], state=" + state + ", numRegisteredFlows=" + numRegisteredFlows.get + ", numCompletedFlows=" + numCompletedFlows.get + ", bytesLeft= " + bytesLeft + ")"
}
