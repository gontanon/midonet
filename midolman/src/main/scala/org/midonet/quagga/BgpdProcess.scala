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

package org.midonet.quagga

import akka.actor.ActorRef
import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.netlink.AfUnix
import org.midonet.util.Waiters.sleepBecause
import org.midonet.util.process.ProcessHelper

class BgpdProcess(routingHandler: ActorRef, vtyPortNumber: Int,
                  listenAddress: String, socketAddress: AfUnix.Address,
                  networkNamespace: String, val config: MidolmanConfig) {
    private final val log = LoggerFactory.getLogger(this.getClass)
    var bgpdProcess: Process = null

    def start(): Boolean = {
        log.debug("Starting bgpd process. Vty: {}", vtyPortNumber)

        val bgpdCmdLine = "ip netns exec " + networkNamespace +
            " " + config.pathToBGPD + "/bgpd" +
            " --vty_port " + vtyPortNumber +
            //" --vty_addr 127.0.0.1" +
            " --config_file " + config.pathToBGPDConfig + "/bgpd.conf" +
            " --pid_file /var/run/quagga/bgpd." + vtyPortNumber + ".pid " +
            " --socket " + socketAddress.getPath

        log.debug("bgpd command line: {}", bgpdCmdLine)

        val daemonRunConfig =
            ProcessHelper.newDemonProcess(bgpdCmdLine, log, "bgpd-" + vtyPortNumber)

        bgpdProcess = daemonRunConfig.run()

        //TODO(abel) it's not enough to launch the process to send a ready
        //TODO(abel) check if it succeeded
        sleepBecause("we need bgpd to boot up", 5)

        if (bgpdProcess != null) {
            log.debug("bgpd process started. Vty: {}", vtyPortNumber)
            true
        } else {
            log.debug("bgpdProcess is null, won't sent BGPD_READY")
            false
        }

    }

    def stop() {
        log.debug("Stopping bgpd process. Vty: {}", vtyPortNumber)

        if (bgpdProcess != null)
            bgpdProcess.destroy()
        else
            log.warn("Couldn't kill bgpd (" + vtyPortNumber + ") because it wasn't started")

        log.debug("bgpd process stopped. Vty: {}", vtyPortNumber)
    }
}

