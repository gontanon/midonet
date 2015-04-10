/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.midonet.cluster.ClusterRouterManager
import org.midonet.cluster.ClusterRouterManager.ArpCacheImpl
import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation.{PacketContext, PacketEmitter, ArpTableImpl}
import org.midonet.midolman.state._
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{Packet, FlowMatch}
import org.midonet.packets.{Ethernet, IPv4Subnet, MAC, IPv4Addr}
import org.midonet.util.eventloop.{TryCatchReactor, Reactor}

import org.apache.curator.test.TestingServer
import org.scalatest.junit.JUnitRunner
import org.scalatest.{ShouldMatchers, BeforeAndAfter, BeforeAndAfterAll, Suite}

import scala.concurrent.ExecutionContext

@RunWith(classOf[JUnitRunner])
class ArpTableTortureTest extends Suite
                           with BeforeAndAfterAll
                           with ShouldMatchers {
    val ZK_ROOT = "/test-" + UUID.randomUUID().toString
    val routerId = UUID.randomUUID()

    var zk: TestingServer = _

    var ts: TestingServer = _
    var directory: Directory = _
    var zkConn: ZkConnection = _
    var arpTable: ArpTable = _
    var arpCache: ArpCache = _
    var reactor: Reactor = _
    var simTable: org.midonet.midolman.simulation.ArpTable = _

    var packetsEmitted = 0

    val routerPort = new RouterPort
    routerPort.id = UUID.randomUUID()
    routerPort.routerId = routerId
    routerPort.portMac = MAC.random()
    routerPort.portIp = IPv4Addr.fromString("192.168.1.254")
    routerPort.portSubnet = IPv4Subnet.fromCidr("192.168.1.254/24")
    routerPort.afterFromProto(null)

    val LAG_MILLIS = 10000

    implicit val packetContext = {
        val frame = Ethernet.random()
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        fmatch.setInputPortNumber(1)
        val context = new PacketContext(-1, new Packet(frame, fmatch), fmatch)
        context.packetEmitter = new NoOpPacketEmitter((_) => packetsEmitted += 1)
        context
    }

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        ts = new TestingServer
        ts.start()

        implicit val as = ActorSystem()
        implicit val ec: ExecutionContext = as.dispatcher

        zkConn = new ZkConnection(ts.getConnectString, 10000, null)
        zkConn.open()
        reactor = new TryCatchReactor("test-arptable-" + routerId.toString , 1)
        directory = new ZkDirectory(zkConn, "", null, reactor)
        directory.add(ZK_ROOT, "".getBytes, CreateMode.PERSISTENT)
        arpTable = new ArpTable(directory.getSubDirectory(ZK_ROOT))
        arpTable.start()
        arpCache = new PoorlyResponsiveArpCache(arpTable, routerId, reactor, LAG_MILLIS)
        simTable = new ArpTableImpl(arpCache, MidolmanConfig.forTests, (a, b, c) => {})
        simTable.start()

    }

    override protected def afterAll(): Unit = {
        zkConn.close()
        ts.stop()
        reactor.shutDownNow()
    }

    def testArpTable: Unit = {
        for (i <- 0 to 999999) {
            intercept[NotYetException] {
                simTable.get(IPv4Addr.fromString("192.168.1.1"), routerPort)
            }
        }
        Thread.sleep(LAG_MILLIS + 20000)
        packetsEmitted should be (1)
    }
}

class NoOpPacketEmitter(cb: (GeneratedPacket) => Unit) extends PacketEmitter {
    def pendingPackets: Int = 0

    def schedule(genPacket: GeneratedPacket): Boolean = { cb(genPacket) ; true }

    def process(emit: GeneratedPacket => Unit): Unit = {}

    def poll(): GeneratedPacket = null
}

class PoorlyResponsiveArpCache(arpTable: ArpTable, id: UUID, val reactor: Reactor,
        lagMillis: Int) extends ArpCacheImpl(arpTable, id, reactor) {
    override def add(ipAddr: IPv4Addr, entry: ArpCacheEntry): Unit = {
        reactor.schedule(addRunnable(ipAddr, entry),
                         lagMillis, TimeUnit.MILLISECONDS)
    }
}
