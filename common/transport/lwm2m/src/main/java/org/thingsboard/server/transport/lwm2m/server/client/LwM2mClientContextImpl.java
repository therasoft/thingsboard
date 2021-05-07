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
package org.thingsboard.server.transport.lwm2m.server.client;

import lombok.RequiredArgsConstructor;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.security.EditableSecurityStore;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.secure.EndpointSecurityInfo;
import org.thingsboard.server.transport.lwm2m.secure.LwM2MSecurityMode;
import org.thingsboard.server.transport.lwm2m.secure.LwM2mCredentialsSecurityInfoValidator;
import org.thingsboard.server.transport.lwm2m.server.LwM2mTransportContext;
import org.thingsboard.server.transport.lwm2m.server.LwM2mTransportUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.thingsboard.server.transport.lwm2m.secure.LwM2MSecurityMode.NO_SEC;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportUtil.convertPathFromObjectIdToIdVer;

@Service
@TbLwM2mTransportComponent
@RequiredArgsConstructor
public class LwM2mClientContextImpl implements LwM2mClientContext {

    private final LwM2mTransportContext context;
    private final Map<String, LwM2mClient> lwM2mClientsByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, LwM2mClient> lwM2mClientsByRegistrationId = new ConcurrentHashMap<>();
    private Map<UUID, LwM2mClientProfile> profiles = new ConcurrentHashMap<>();

    private final LwM2mCredentialsSecurityInfoValidator lwM2MCredentialsSecurityInfoValidator;

    private final EditableSecurityStore securityStore;

    @Override
    public LwM2mClient getClientByEndpoint(String endpoint) {
        return lwM2mClientsByEndpoint.get(endpoint);
    }

    @Override
    public LwM2mClient getClientByRegistrationId(String registrationId) {
        return lwM2mClientsByRegistrationId.get(registrationId);
    }

    @Override
    public LwM2mClient getOrRegister(Registration registration) {
        if (registration == null) {
            return null;
        }
        LwM2mClient client = lwM2mClientsByRegistrationId.get(registration.getId());
        if (client == null) {
            client = lwM2mClientsByEndpoint.get(registration.getEndpoint());
            if (client == null) {
                client = registerOrUpdate(registration);
            }
        }
        return client;
    }

    @Override
    public LwM2mClient getClient(TransportProtos.SessionInfoProto sessionInfo) {
        return getClient(new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB()));
    }

    @Override
    public LwM2mClient getClient(UUID sessionId) {
        //TODO: refactor this to search by sessionId efficiently.
        return lwM2mClientsByEndpoint.values().stream().filter(c -> c.getSessionId().equals(sessionId)).findAny().get();
    }

    @Override
    public LwM2mClient registerOrUpdate(Registration registration) {
        LwM2mClient lwM2MClient = lwM2mClientsByEndpoint.get(registration.getEndpoint());
        if (lwM2MClient == null) {
            lwM2MClient = this.fetchClientByEndpoint(registration.getEndpoint());
        }
        lwM2MClient.setRegistration(registration);
//        TODO: this remove is probably redundant. We should remove it.
//        this.lwM2mClientsByEndpoint.remove(registration.getEndpoint());
        this.lwM2mClientsByRegistrationId.put(registration.getId(), lwM2MClient);
        return lwM2MClient;
    }

    public Registration getRegistration(String registrationId) {
        return this.lwM2mClientsByRegistrationId.get(registrationId).getRegistration();
    }

    @Override
    public LwM2mClient fetchClientByEndpoint(String endpoint) {
        EndpointSecurityInfo securityInfo = lwM2MCredentialsSecurityInfoValidator.getEndpointSecurityInfo(endpoint, LwM2mTransportUtil.LwM2mTypeServer.CLIENT);
        if (securityInfo.getSecurityMode() < LwM2MSecurityMode.DEFAULT_MODE.code) {
            if (securityInfo.getDeviceProfile() != null) {
                toClientProfile(securityInfo.getDeviceProfile());
                UUID profileUuid = securityInfo.getDeviceProfile().getUuidId();
                LwM2mClient client;
                if (securityInfo.getSecurityInfo() != null) {
                    client = new LwM2mClient(context.getNodeId(), securityInfo.getSecurityInfo().getEndpoint(),
                            securityInfo.getSecurityInfo().getIdentity(), securityInfo.getSecurityInfo(),
                            securityInfo.getMsg(), profileUuid, UUID.randomUUID());
                } else if (securityInfo.getSecurityMode() == NO_SEC.code) {
                    client = new LwM2mClient(context.getNodeId(), endpoint,
                            null, null,
                            securityInfo.getMsg(), profileUuid, UUID.randomUUID());
                } else {
                    throw new RuntimeException(String.format("Registration failed: device %s not found.", endpoint));
                }
                lwM2mClientsByEndpoint.put(client.getEndpoint(), client);
                return client;
            } else {
                throw new RuntimeException(String.format("Registration failed: device %s not found.", endpoint));
            }
        } else {
            throw new RuntimeException(String.format("Registration failed: FORBIDDEN, endpointId: %s", endpoint));
        }
    }

    @Override
    public Collection<LwM2mClient> getLwM2mClients() {
        return lwM2mClientsByEndpoint.values();
    }

    @Override
    public Map<UUID, LwM2mClientProfile> getProfiles() {
        return profiles;
    }

    @Override
    public LwM2mClientProfile getProfile(UUID profileId) {
        return profiles.get(profileId);
    }

    @Override
    public LwM2mClientProfile getProfile(Registration registration) {
        return this.getProfiles().get(getOrRegister(registration).getProfileId());
    }

    @Override
    public Map<UUID, LwM2mClientProfile> setProfiles(Map<UUID, LwM2mClientProfile> profiles) {
        return this.profiles = profiles;
    }

    @Override
    public LwM2mClientProfile toClientProfile(DeviceProfile deviceProfile) {
        LwM2mClientProfile lwM2MClientProfile = profiles.get(deviceProfile.getUuidId());
        if (lwM2MClientProfile == null) {
            lwM2MClientProfile = LwM2mTransportUtil.toLwM2MClientProfile(deviceProfile);
            profiles.put(deviceProfile.getUuidId(), lwM2MClientProfile);
        }
        return lwM2MClientProfile;
    }

    /**
     * if isVer - ok or default ver=DEFAULT_LWM2M_VERSION
     *
     * @param registration -
     * @return - all objectIdVer in client
     */
    @Override
    public Set<String> getSupportedIdVerInClient(Registration registration) {
        Set<String> clientObjects = ConcurrentHashMap.newKeySet();
        Arrays.stream(registration.getObjectLinks()).forEach(url -> {
            LwM2mPath pathIds = new LwM2mPath(url.getUrl());
            if (!pathIds.isRoot()) {
                clientObjects.add(convertPathFromObjectIdToIdVer(url.getUrl(), registration));
            }
        });
        return (clientObjects.size() > 0) ? clientObjects : null;
    }

    @Override
    public LwM2mClient getClientByDeviceId(UUID deviceId) {
        return lwM2mClientsByRegistrationId.values().stream().filter(e -> deviceId.equals(e.getDeviceId())).findFirst().orElse(null);
    }

    @Override
    public void removeClientByRegistrationId(String registrationId) {
        LwM2mClient lwM2MClient = this.lwM2mClientsByRegistrationId.get(registrationId);
        if (lwM2MClient != null) {
            this.securityStore.remove(lwM2MClient.getEndpoint(), false);
            this.lwM2mClientsByEndpoint.remove(lwM2MClient.getEndpoint());
            this.lwM2mClientsByRegistrationId.remove(registrationId);
            UUID profileId = lwM2MClient.getProfileId();
            if (profileId != null) {
                Optional<LwM2mClient> otherClients = lwM2mClientsByRegistrationId.values().stream().filter(e -> e.getProfileId().equals(profileId)).findFirst();
                if (otherClients.isEmpty()) {
                    profiles.remove(profileId);
                }
            }
        }
    }
}
