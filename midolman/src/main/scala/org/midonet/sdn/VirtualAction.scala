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
package org.midonet.sdn.flows;

import java.nio.ByteBuffer;
import java.util.UUID

import org.midonet.odp.flows.FlowAction

/** This objects holds various classes reprensenting "virtual" flow actions
 *  returned as part of a Simulation. These objects are then translated by
 *  the trait FlowTranslator into "real" odp.flows.FlowAction objects that
 *  can be understood by the datapath module. */
object VirtualActions {

    /** output action to a set of virtual ports with uuid portSetId */
    case class FlowActionOutputToVrnPortSet(portSetId:UUID)
            extends VirtualFlowAction

    /** output action to a single virtual ports with uuid portId */
    case class FlowActionOutputToVrnPort(portId: UUID)
            extends VirtualFlowAction

    /** impedance matching trait to make Virtual Action subclasses of FlowAction
     *  and make them fit into collections of FlowAction. */
    trait VirtualFlowAction extends FlowAction {
        def attrId = 0
        def serializeInto(buf: ByteBuffer) = 0
        def deserializeFrom(buf: ByteBuffer) { }
    }
}
