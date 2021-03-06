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
package org.midonet.midolman.host.guice;

import com.google.inject.Singleton;

import org.midonet.midolman.host.services.HostAgentService;
import org.midonet.midolman.services.SelectLoopService;

/**
 * Module to configure dependencies for the host agent.
 */
public class HostAgentModule extends HostModule {

    @Override
    protected void configure() {
        super.configure();

        bind(SelectLoopService.class).in(Singleton.class);
        expose(SelectLoopService.class);

        bind(HostAgentService.class).asEagerSingleton();
        expose(HostAgentService.class);
    }

}
