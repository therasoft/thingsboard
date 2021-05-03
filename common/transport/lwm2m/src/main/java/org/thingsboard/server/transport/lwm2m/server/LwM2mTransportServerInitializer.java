/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.server.transport.lwm2m.server;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.secure.LWM2MGenerationPSkRPkECC;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Component("LwM2MTransportServerInitializer")
@TbLwM2mTransportComponent
public class LwM2mTransportServerInitializer {

    @Autowired
    private LwM2mTransportServiceImpl service;

    @Autowired
    private LeshanServer leshanServer;

    @Autowired
    private LwM2mTransportContextServer context;

    @PostConstruct
    public void init() {
        if (this.context.getLwM2MTransportServerConfig().getEnableGenNewKeyPskRpk()) {
            new LWM2MGenerationPSkRPkECC();
        }
        this.startLhServer();
    }

    private void startLhServer() {
        this.leshanServer.start();
        LwM2mServerListener lhServerCertListener = new LwM2mServerListener(service);
        this.leshanServer.getRegistrationService().addListener(lhServerCertListener.registrationListener);
        this.leshanServer.getPresenceService().addListener(lhServerCertListener.presenceListener);
        this.leshanServer.getObservationService().addListener(lhServerCertListener.observationListener);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Stopping LwM2M transport Server!");
        leshanServer.destroy();
        log.info("LwM2M transport Server stopped!");
    }
}
