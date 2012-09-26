// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor._
import akka.util.Duration
import collection.JavaConversions._
import collection.{Set => ROSet, mutable}
import collection.mutable.{HashMap, MultiMap}
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import com.midokura.midolman.config.MidolmanConfig
import com.midokura.midolman.datapath.ErrorHandlingCallback
import com.midokura.netlink.Callback
import com.midokura.netlink.exceptions.NetlinkException
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.sdn.dp.{Datapath, Flow, FlowMatch, Packet}
import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.sdn.flows.{FlowManager, FlowManagerHelper, WildcardFlow,
                               WildcardMatches}
import com.midokura.util.functors.Callback0
import akka.event.LoggingReceive


object FlowController extends Referenceable {
    val Name = "FlowController"

    /**
     *
     * @param flow The flow rule itself.
     * @param cookie The cookie passed in the PacketIn sent to the
     *               DatataphController. Used to match the flow rule with the
     *               packet that generated it. The cookie may be null if the
     *               flow was not generated by a packet.
     * @param pktBytes Optional data to be sent as a packet using the same
     *                 actions from the flow rule.
     * @param flowRemovalCallbacks
     * @param tags
     */
    case class AddWildcardFlow(flow: WildcardFlow,
                               cookie: Option[Int],
                               pktBytes: Array[Byte],
                               flowRemovalCallbacks: ROSet[Callback0],
                               tags: ROSet[Any])

    case class RemoveWildcardFlow(flow: WildcardFlow)

    case class RemoveFlow(flow: Flow, cb: Callback[Flow])

    case class SendPacket(data: Array[Byte], actions: List[FlowAction[_]])

    case class DiscardPacket(cookie: Option[Int])

    case class InvalidateFlowsByTag(tag: AnyRef)

    case class CheckFlowExpiration()

    case class WildcardFlowAdded(f: WildcardFlow)

    case class  WildcardFlowRemoved(f: WildcardFlow)

    case class FlowUpdateCompleted(flow: Flow)
}


class FlowController extends Actor with ActorLogging {

    import FlowController._

    var datapath: Datapath = null
    var maxDpFlows = 0
    var dpFlowRemoveBatchSize = 0
    var cookieCounter = 0 // Every PacketIn we send up gets a unique cookie

    @Inject
    var midolmanConfig: MidolmanConfig = null

    type Cookie = Int
    private val dpMatchToCookie = HashMap[FlowMatch, Cookie]()
    private val cookieToPendedPackets: MultiMap[Cookie, Packet] =
        new HashMap[Cookie, mutable.Set[Packet]] with MultiMap[Cookie, Packet]

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    var flowManager: FlowManager = null

    val tagToFlows: MultiMap[AnyRef, WildcardFlow] =
        new HashMap[AnyRef, mutable.Set[WildcardFlow]]
            with MultiMap[AnyRef, WildcardFlow]
    val flowToTags: MultiMap[WildcardFlow, AnyRef] =
        new HashMap[WildcardFlow, mutable.Set[AnyRef]]
            with MultiMap[WildcardFlow, AnyRef]

    var flowExpirationCheckInterval: Duration = null


    override def preStart() {
        super.preStart()
        //ComponentInjectorHolder.inject(this)
        maxDpFlows = midolmanConfig.getDatapathMaxFlowCount

        flowExpirationCheckInterval = Duration(midolmanConfig.getFlowExpirationInterval,
            TimeUnit.MILLISECONDS)


        flowManager = new FlowManager(new FlowManagerInfoImpl(), maxDpFlows)
    }

    def receive = LoggingReceive {
        case DatapathController.DatapathReady(dp) =>
            if (null == datapath) {
                datapath = dp
                installPacketInHook()
                log.info("Datapath hook installed")
                // schedule next check for flow expiration after 20 ms and then after
                // every flowExpirationCheckInterval ms
                context.system.scheduler.schedule(Duration(20, TimeUnit.MILLISECONDS),
                    flowExpirationCheckInterval,
                    self,
                    new CheckFlowExpiration)
            }

        case packetIn(packet) =>
            handlePacketIn(packet)

        case AddWildcardFlow(wildcardFlow, cookie, pktBytes,
                             flowRemovalCallbacks, tags) =>
            handleNewWildcardFlow(wildcardFlow, cookie)
            context.system.eventStream.publish(new WildcardFlowAdded(wildcardFlow))

        case DiscardPacket(cookieOpt) =>
            freePendedPackets(cookieOpt)

        case InvalidateFlowsByTag(tag) =>
            val flowsOption = tagToFlows.get(tag)
            flowsOption match {
                case None =>
                case Some(flowSet) =>
                    for (wildFlow <- flowSet)
                        removeWildcardFlow(wildFlow)
            }

        case RemoveFlow(flow: Flow, cb: Callback[Flow]) =>
            removeFlow(flow, cb)

        case RemoveWildcardFlow(flow) =>
            log.debug("Removing wcflow {}", flow)
            removeWildcardFlow(flow)
            context.system.eventStream.publish(new WildcardFlowRemoved(flow))

        case SendPacket(data, actions) =>
            if (actions.size > 0) {
                val packet = new Packet().
                    setMatch(new FlowMatch).
                    setData(data).setActions(actions)
                datapathConnection.packetsExecute(datapath, packet,
                    new ErrorHandlingCallback[java.lang.Boolean] {
                        def onSuccess(data: java.lang.Boolean) {}

                        def handleError(ex: NetlinkException, timeout: Boolean) {
                            log.error(ex,
                                "Failed to send a packet {} due to {}", packet,
                                if (timeout) "timeout" else "error")
                        }
                    })
            }
        case CheckFlowExpiration() =>
            flowManager.checkFlowsExpiration()

        case flowUpdated(flow) =>
            flowManager.updateFlowLastUsedTimeCompleted(flow)
            context.system.eventStream.publish(new FlowUpdateCompleted(flow))

        case flowAdded(flow) =>
            flowManager.addFlowCompleted(flow)

        case flowRemoved(flow) =>
            flowManager.removeFlowCompleted(flow)
    }

    private def freePendedPackets(cookieOpt: Option[Cookie]): Unit = {
        cookieOpt match {
            case None => // no pended packets
            case Some(cookie) =>
                val pended = cookieToPendedPackets.remove(cookie)
                val packet = pended.head.last
                dpMatchToCookie.remove(packet.getMatch)
        }
    }
    /**
     * Internal message posted by the netlink callback hook when a new packet not
     * matching any flows appears on one of the datapath ports.
     *
     * @param packet the packet data
     */
    case class packetIn(packet: Packet)
    case class flowUpdated(flow: Flow)
    case class flowAdded(flow: Flow)
    case class flowRemoved(flow: Flow)

    private def removeWildcardFlow(wildFlow: WildcardFlow) {
        log.info("removeWildcardFlow - Removing flow {}", wildFlow)
        flowManager.remove(wildFlow)
        val tags = flowToTags.remove(wildFlow)
        for (tag <- tags){
            tagToFlows.remove(tag)
        }
    }

    private def removeFlow(flow: Flow, cb: Callback[Flow]){
        datapathConnection.flowsDelete(datapath, flow, cb)
    }

    private def handlePacketIn(packet: Packet) {
        // In case the PacketIn notify raced a flow rule installation, see if
        // the flowManager already has a match.
        val actions = flowManager.getActionsForDpFlow(packet.getMatch)
        if (actions != null && actions.size() > 0) {
            packet.setActions(actions)
            datapathConnection.packetsExecute(datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {}

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} due to {}", packet,
                            if (timeout) "timeout" else "error")
                    }
                })
            return
        }
        // Otherwise, try to create a datapath flow based on an existing
        // wildcard flow.
        val dpFlow = flowManager.createDpFlow(packet.getMatch)
        if (dpFlow != null) {
            datapathConnection.flowsCreate(datapath, dpFlow,
            new ErrorHandlingCallback[Flow] {
                def onSuccess(data: Flow) {
                    self ! flowAdded(data)
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error("Got an exception {} or timeout {} when trying to add flow" +
                            "with flow match {}", ex, timeout, dpFlow.getMatch)
                }
            })
            return
        } else {
            // Otherwise, pass the packetIn up to the next layer for handling.
            // Keep track of these packets so that for every FlowMatch, only
            // one such call goes to the next layer.
            dpMatchToCookie.get(packet.getMatch) match {
                case None =>
                    cookieCounter += 1
                    val cookie = cookieCounter
                    dpMatchToCookie.put(packet.getMatch, cookie)
                    DatapathController.getRef().tell(
                        DatapathController.PacketIn(
                            WildcardMatches.fromFlowMatch(packet.getMatch),
                            packet.getData, packet.getMatch, packet.getReason,
                            Some(cookie)))
                    cookieToPendedPackets.addBinding(cookie, packet)

                case Some(cookie) =>
                    // Simulation in progress. Just pend the packet.
                    cookieToPendedPackets.addBinding(cookie, packet)
            }
        }
    }

    private def handleNewWildcardFlow(wildcardFlow: WildcardFlow,
                                      cookieOpt: Option[Cookie]) {
        if (!flowManager.add(wildcardFlow)){
            log.error("FlowManager failed to install wildcard flow {}",
                wildcardFlow)
            // TODO(pino, ross): should we send Packet commands for pended?
            // For now, just free the pended packets.
            freePendedPackets(cookieOpt)
            return
        }
        // Now install any datapath flows that are needed.
        cookieOpt match {
            case None => // No packets pended. Do nothing.
            case Some(cookie) =>
                val pendedPackets =
                    cookieToPendedPackets.remove(cookie)
                val packet = pendedPackets.head.last
                dpMatchToCookie.remove(packet.getMatch)
                flowManager.add(packet.getMatch, wildcardFlow)

                val dpFlow = new Flow().
                    setMatch(packet.getMatch).
                    setActions(wildcardFlow.getActions).
                    setLastUsedTime(System.currentTimeMillis())

                datapathConnection.flowsCreate(datapath, dpFlow,
                new ErrorHandlingCallback[Flow] {
                    def onSuccess(data: Flow) {
                        self ! flowAdded(data)
                    }

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error("Got an exception {} or timeout {} when trying to add flow" +
                            "with flow match {}", ex, timeout, dpFlow.getMatch)
                    }
                })
                log.debug("Flow created {}", dpFlow)

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (wildcardFlow.getActions.size() > 0) {
                    for (unpendedPacket <- pendedPackets.get) {
                        unpendedPacket.setActions(wildcardFlow.getActions)

                        datapathConnection.packetsExecute(datapath, unpendedPacket,
                            new ErrorHandlingCallback[java.lang.Boolean] {
                                def onSuccess(data: java.lang.Boolean) {}

                                def handleError(ex: NetlinkException, timeout: Boolean) {
                                    log.error(ex,
                                        "Failed to send a packet {} due to {}", packet,
                                        if (timeout) "timeout" else "error")
                                }
                            })
                    }
                }
        }
    }

    private def installPacketInHook() = {
        log.info("Installing packet in handler")
        // TODO: try to make this cleaner (right now we are just waiting for
        // the install future thus blocking the current thread).
        datapathConnection.datapathsSetNotificationHandler(datapath,
            new Callback[Packet] {
                def onSuccess(data: Packet) {
                    self ! packetIn(data)
                }

                def onTimeout() {}

                def onError(e: NetlinkException) {}
            }).get()
    }

    class FlowManagerInfoImpl() extends FlowManagerHelper{
        def removeFlow(flow: Flow) {
            datapathConnection.flowsDelete(datapath, flow,
            new ErrorHandlingCallback[Flow] {
                def handleError(ex: NetlinkException, timeout: Boolean) {
                    log.error("Got an exception {} or timeout {} when trying to remove flow" +
                        "with flow match {}", ex, timeout, flow.getMatch)
                }

                def onSuccess(data: Flow) {
                    self ! flowRemoved(data)
                }
            })
        }

        def removeWildcardFlow(flow: WildcardFlow) {
            self ! RemoveWildcardFlow(flow)
        }

        def getFlow(flowMatch: FlowMatch) {

                datapathConnection.flowsGet(datapath, flowMatch,
                new ErrorHandlingCallback[Flow] {

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error("Got an exception {} or timeout {} when trying to flowsGet()" +
                            "for flow match {}", ex, timeout, flowMatch)
                    }

                    def onSuccess(data: Flow) {
                        self ! flowUpdated(data)
                    }

                })

        }
    }

}
