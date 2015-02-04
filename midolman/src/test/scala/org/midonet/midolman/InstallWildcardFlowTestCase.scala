/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import org.midonet.cluster.data.host.Host
import org.midonet.midolman.FlowController.{WildcardFlowAdded, AddWildcardFlow}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}
import org.midonet.odp.flows.FlowActions.output

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowTestCase extends MidolmanTestCase {
    def testInstallFlowForLocalPort() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val inputPort = Ports.bridgePort(bridge)
        inputPort.setId(clusterDataClient().portsCreate(inputPort))

        val outputPort = Ports.bridgePort(bridge)
        outputPort.setId(clusterDataClient().portsCreate(outputPort))

        materializePort(inputPort, host, "inputPort")
        materializePort(outputPort, host, "outputPort")

        drainProbes()
        initializeDatapath() should not be (null)
        datapathReadyEventsProbe.expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val inputPortNo = getPortNumber("inputPort")
        val outputPortNo = getPortNumber("outputPort")

        val vrnPortOutput = new FlowActionOutputToVrnPort(outputPort.getId)
        val dpPortOutput = output(outputPortNo)

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbes()

        addVirtualWildcardFlow(new WildcardMatch().setInputPortUUID(inputPort.getId),
                               vrnPortOutput)

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f.getMatch.getInputPortUUID should be(null)
        addFlowMsg.f.getMatch.getInputPortNumber should be(inputPortNo)

        val actions = addFlowMsg.f.getActions
        actions should not be null
        actions.contains(dpPortOutput) should be (true)
        actions.contains(vrnPortOutput) should be (false)
    }
}
