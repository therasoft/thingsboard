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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.util.NamedThreadFactory;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cache.firmware.FirmwareDataCache;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.firmware.FirmwareKey;
import org.thingsboard.server.common.data.firmware.FirmwareType;
import org.thingsboard.server.common.data.firmware.FirmwareUtil;
import org.thingsboard.server.common.data.id.FirmwareId;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.AdaptorException;
import org.thingsboard.server.common.transport.service.DefaultTransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.AttributeUpdateNotificationMsg;
import org.thingsboard.server.gen.transport.TransportProtos.SessionEvent;
import org.thingsboard.server.gen.transport.TransportProtos.SessionInfoProto;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.config.LwM2MTransportServerConfig;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientContext;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientProfile;
import org.thingsboard.server.transport.lwm2m.server.client.Lwm2mClientRpcRequest;
import org.thingsboard.server.transport.lwm2m.server.client.ResultsAddKeyValueProto;
import org.thingsboard.server.transport.lwm2m.server.client.ResultsAnalyzerParameters;
import org.thingsboard.server.transport.lwm2m.utils.LwM2mValueConverterImpl;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST;
import static org.eclipse.leshan.core.attributes.Attribute.OBJECT_VERSION;
import static org.thingsboard.server.common.data.lwm2m.LwM2mConstants.LWM2M_SEPARATOR_KEY;
import static org.thingsboard.server.common.data.lwm2m.LwM2mConstants.LWM2M_SEPARATOR_PATH;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.CLIENT_NOT_AUTHORIZED;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.DEVICE_ATTRIBUTES_REQUEST;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.FR_OBJECT_ID;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.FR_PATH_RESOURCE_VER_ID;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_ERROR;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_INFO;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_VALUE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LWM2M_STRATEGY_2;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.DISCOVER;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.EXECUTE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.OBSERVE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.OBSERVE_CANCEL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.OBSERVE_READ_ALL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.READ;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.WRITE_ATTRIBUTES;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.WRITE_REPLACE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.WRITE_UPDATE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.SERVICE_CHANNEL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.convertJsonArrayToSet;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.convertPathFromIdVerToObjectId;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.convertPathFromObjectIdToIdVer;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.getAckCallback;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.validateObjectVerFromKey;

@Slf4j
@Service
@TbLwM2mTransportComponent
public class DefaultLwM2MTransportMsgHandler implements LwM2mTransportMsgHandler {

    private ExecutorService executorRegistered;
    private ExecutorService executorUpdateRegistered;
    private ExecutorService executorUnRegistered;
    private LwM2mValueConverterImpl converter;

    private final TransportService transportService;
    private final LwM2mTransportContext context;
    private final LwM2MTransportServerConfig config;
    private final FirmwareDataCache firmwareDataCache;
    private final LwM2mTransportServerHelper helper;
    private final LwM2mClientContext lwM2mClientContext;
    private final LwM2mTransportRequest lwM2mTransportRequest;

    public DefaultLwM2MTransportMsgHandler(TransportService transportService, LwM2MTransportServerConfig config, LwM2mTransportServerHelper helper,
                                           LwM2mClientContext lwM2mClientContext,
                                           @Lazy LwM2mTransportRequest lwM2mTransportRequest,
                                           FirmwareDataCache firmwareDataCache,
                                           LwM2mTransportContext context) {
        this.transportService = transportService;
        this.config = config;
        this.helper = helper;
        this.lwM2mClientContext = lwM2mClientContext;
        this.lwM2mTransportRequest = lwM2mTransportRequest;
        this.firmwareDataCache = firmwareDataCache;
        this.context = context;
    }

    @PostConstruct
    public void init() {
        this.context.getScheduler().scheduleAtFixedRate(this::checkInactivityAndReportActivity, new Random().nextInt((int) config.getSessionReportTimeout()), config.getSessionReportTimeout(), TimeUnit.MILLISECONDS);
        this.executorRegistered = Executors.newFixedThreadPool(this.config.getRegisteredPoolSize(),
                new NamedThreadFactory(String.format("LwM2M %s channel registered", SERVICE_CHANNEL)));
        this.executorUpdateRegistered = Executors.newFixedThreadPool(this.config.getUpdateRegisteredPoolSize(),
                new NamedThreadFactory(String.format("LwM2M %s channel update registered", SERVICE_CHANNEL)));
        this.executorUnRegistered = Executors.newFixedThreadPool(this.config.getUnRegisteredPoolSize(),
                new NamedThreadFactory(String.format("LwM2M %s channel un registered", SERVICE_CHANNEL)));
        this.converter = LwM2mValueConverterImpl.getInstance();
    }

    /**
     * Start registration device
     * Create session: Map<String <registrationId >, LwM2MClient>
     * 1. replaceNewRegistration -> (solving the problem of incorrect termination of the previous session with this endpoint)
     * 1.1 When we initialize the registration, we register the session by endpoint.
     * 1.2 If the server has incomplete requests (canceling the registration of the previous session),
     * delete the previous session only by the previous registration.getId
     * 1.2 Add Model (Entity) for client (from registration & observe) by registration.getId
     * 1.2 Remove from sessions Model by enpPoint
     * Next ->  Create new LwM2MClient for current session -> setModelClient...
     *
     * @param registration         - Registration LwM2M Client
     * @param previousObservations - may be null
     */
    public void onRegistered(Registration registration, Collection<Observation> previousObservations) {
        executorRegistered.submit(() -> {
            try {
                log.warn("[{}] [{{}] Client: create after Registration", registration.getEndpoint(), registration.getId());
                LwM2mClient lwM2MClient = this.lwM2mClientContext.updateInSessionsLwM2MClient(registration);
                if (lwM2MClient != null) {
                    SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration);
                    if (sessionInfo != null) {
                        this.initLwM2mClient(lwM2MClient, sessionInfo);
                        transportService.registerAsyncSession(sessionInfo, new LwM2mSessionMsgListener(this, sessionInfo));
                        transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.OPEN), null);
                        transportService.process(sessionInfo, TransportProtos.SubscribeToAttributeUpdatesMsg.newBuilder().build(), null);
                        transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder().build(), null);
                        this.getInfoFirmwareUpdate(lwM2MClient);
                        this.initLwM2mFromClientValue(registration, lwM2MClient);
                        this.sendLogsToThingsboard(LOG_LW2M_INFO + ": Client create after Registration", registration.getId());
                    } else {
                        log.error("Client: [{}] onRegistered [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), null);
                    }
                } else {
                    log.error("Client: [{}] onRegistered [{}] name  [{}] lwM2MClient ", registration.getId(), registration.getEndpoint(), null);
                }
            } catch (Throwable t) {
                log.error("[{}] endpoint [{}] error Unable registration.", registration.getEndpoint(), t);
            }
        });
    }

    /**
     * if sessionInfo removed from sessions, then new registerAsyncSession
     *
     * @param registration - Registration LwM2M Client
     */
    public void updatedReg(Registration registration) {
        executorUpdateRegistered.submit(() -> {
            try {
                SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration);
                if (sessionInfo != null) {
                    this.checkInactivity(sessionInfo);
                    LwM2mClient lwM2MClient = this.lwM2mClientContext.getLwM2MClient(sessionInfo);
                    if (lwM2MClient.getDeviceId() == null && lwM2MClient.getProfileId() == null) {
                        initLwM2mClient(lwM2MClient, sessionInfo);
                    } else {
                        if (registration.getBindingMode().useQueueMode()) {
                            LwM2mQueuedRequest request;
                            while ((request = lwM2MClient.getQueuedRequests().poll()) != null) {
                                request.send();
                            }
                        }
                    }

                    log.info("Client: [{}] updatedReg [{}] name  [{}] profile ", registration.getId(), registration.getEndpoint(), sessionInfo.getDeviceType());
                } else {
                    log.error("Client: [{}] updatedReg [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), null);
                }
            } catch (Throwable t) {
                log.error("[{}] endpoint [{}] error Unable update registration.", registration.getEndpoint(), t);
            }
        });
    }

    /**
     * @param registration - Registration LwM2M Client
     * @param observations - All paths observations before unReg
     *                     !!! Warn: if have not finishing unReg, then this operation will be finished on next Client`s connect
     */
    public void unReg(Registration registration, Collection<Observation> observations) {
        executorUnRegistered.submit(() -> {
            try {
                this.setCancelObservations(registration);
                this.sendLogsToThingsboard(LOG_LW2M_INFO + ": Client unRegistration", registration.getId());
                this.closeClientSession(registration);
            } catch (Throwable t) {
                log.error("[{}] endpoint [{}] error Unable un registration.", registration.getEndpoint(), t);
            }
        });
    }

    private void initLwM2mClient(LwM2mClient lwM2MClient, SessionInfoProto sessionInfo) {
        lwM2MClient.setDeviceId(new UUID(sessionInfo.getDeviceIdMSB(), sessionInfo.getDeviceIdLSB()));
        lwM2MClient.setProfileId(new UUID(sessionInfo.getDeviceProfileIdMSB(), sessionInfo.getDeviceProfileIdLSB()));
        lwM2MClient.setDeviceName(sessionInfo.getDeviceName());
        lwM2MClient.setDeviceProfileName(sessionInfo.getDeviceType());
    }

    private void closeClientSession(Registration registration) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration);
        if (sessionInfo != null) {
            transportService.deregisterSession(sessionInfo);
            this.doCloseSession(sessionInfo);
            lwM2mClientContext.delRemoveSessionAndListener(registration.getId());
            if (lwM2mClientContext.getProfiles().size() > 0) {
                this.syncSessionsAndProfiles();
            }
            log.info("Client close session: [{}] unReg [{}] name  [{}] profile ", registration.getId(), registration.getEndpoint(), sessionInfo.getDeviceType());
        } else {
            log.error("Client close session: [{}] unReg [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), null);
        }
    }

    @Override
    public void onSleepingDev(Registration registration) {
        log.info("[{}] [{}] Received endpoint Sleeping version event", registration.getId(), registration.getEndpoint());
        this.sendLogsToThingsboard(LOG_LW2M_INFO + ": Client is sleeping!", registration.getId());

        //TODO: associate endpointId with device information.
    }

    @Override
    public void setCancelObservations(Registration registration) {
        if (registration != null) {
            Set<Observation> observations = context.getServer().getObservationService().getObservations(registration);
            observations.forEach(observation -> lwM2mTransportRequest.sendAllRequest(registration,
                    convertPathFromObjectIdToIdVer(observation.getPath().toString(), registration), OBSERVE_CANCEL,
                    null, null, this.config.getTimeout(), null));
        }
    }

    /**
     * Sending observe value to thingsboard from ObservationListener.onResponse: object, instance, SingleResource or MultipleResource
     *
     * @param registration - Registration LwM2M Client
     * @param path         - observe
     * @param response     - observe
     */
    @Override
    public void onUpdateValueAfterReadResponse(Registration registration, String path, ReadResponse response, Lwm2mClientRpcRequest rpcRequest) {
        if (response.getContent() != null) {
            if (response.getContent() instanceof LwM2mObject) {
                LwM2mObject lwM2mObject = (LwM2mObject) response.getContent();
                this.updateObjectResourceValue(registration, lwM2mObject, path);
            } else if (response.getContent() instanceof LwM2mObjectInstance) {
                LwM2mObjectInstance lwM2mObjectInstance = (LwM2mObjectInstance) response.getContent();
                this.updateObjectInstanceResourceValue(registration, lwM2mObjectInstance, path);
            } else if (response.getContent() instanceof LwM2mResource) {
                LwM2mResource lwM2mResource = (LwM2mResource) response.getContent();
                if (rpcRequest != null) {
                    Object valueResp = lwM2mResource.isMultiInstances() ? lwM2mResource.getValues() : lwM2mResource.getValue();
                    Object value = this.converter.convertValue(valueResp, lwM2mResource.getType(), ResourceModel.Type.STRING,
                            new LwM2mPath(convertPathFromIdVerToObjectId(path)));
                    rpcRequest.setValueMsg(String.format("%s", value));
                    this.sentRpcRequest(rpcRequest, response.getCode().getName(), (String) value, LOG_LW2M_VALUE);
                }
                this.updateResourcesValue(registration, lwM2mResource, path);
            }
        }
    }

    /**
     * Update - send request in change value resources in Client
     * 1. FirmwareUpdate:
     * - If msg.getSharedUpdatedList().forEach(tsKvProto -> {tsKvProto.getKv().getKey().indexOf(FIRMWARE_UPDATE_PREFIX, 0) == 0
     * 2. Shared Other AttributeUpdate
     * -- Path to resources from profile equal keyName or from ModelObject equal name
     * -- Only for resources:  isWritable && isPresent as attribute in profile -> LwM2MClientProfile (format: CamelCase)
     * 3. Delete - nothing
     *
     * @param msg -
     */
    @Override
    public void onAttributeUpdate(AttributeUpdateNotificationMsg msg, TransportProtos.SessionInfoProto sessionInfo) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClient(new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB()));
        if (msg.getSharedUpdatedCount() > 0) {
            msg.getSharedUpdatedList().forEach(tsKvProto -> {
                String pathName = tsKvProto.getKv().getKey();
                String pathIdVer = this.getPresentPathIntoProfile(sessionInfo, pathName);
                Object valueNew = this.helper.getValueFromKvProto(tsKvProto.getKv());
                //TODO: react on change of the firmware name.
                if (FirmwareUtil.getAttributeKey(FirmwareType.FIRMWARE, FirmwareKey.VERSION).equals(pathName) && !valueNew.equals(lwM2MClient.getFrUpdate().getCurrentFwVersion())) {
                    this.getInfoFirmwareUpdate(lwM2MClient);
                }
                if (pathIdVer != null) {
                    ResourceModel resourceModel = lwM2MClient.getResourceModel(pathIdVer, this.config
                            .getModelProvider());
                    if (resourceModel != null && resourceModel.operations.isWritable()) {
                        this.updateResourcesValueToClient(lwM2MClient, this.getResourceValueFormatKv(lwM2MClient, pathIdVer), valueNew, pathIdVer);
                    } else {
                        log.error("Resource path - [{}] value - [{}] is not Writable and cannot be updated", pathIdVer, valueNew);
                        String logMsg = String.format("%s: attributeUpdate: Resource path - %s value - %s is not Writable and cannot be updated",
                                LOG_LW2M_ERROR, pathIdVer, valueNew);
                        this.sendLogsToThingsboard(logMsg, lwM2MClient.getRegistration().getId());
                    }
                } else {
                    log.error("Resource name name - [{}] value - [{}] is not present as attribute/telemetry in profile and cannot be updated", pathName, valueNew);
                    String logMsg = String.format("%s: attributeUpdate: attribute name - %s value - %s is not present as attribute in profile and cannot be updated",
                            LOG_LW2M_ERROR, pathName, valueNew);
                    this.sendLogsToThingsboard(logMsg, lwM2MClient.getRegistration().getId());
                }

            });
        } else if (msg.getSharedDeletedCount() > 0) {
            msg.getSharedUpdatedList().forEach(tsKvProto -> {
                String pathName = tsKvProto.getKv().getKey();
                Object valueNew = this.helper.getValueFromKvProto(tsKvProto.getKv());
                if (FirmwareUtil.getAttributeKey(FirmwareType.FIRMWARE, FirmwareKey.VERSION).equals(pathName) && !valueNew.equals(lwM2MClient.getFrUpdate().getCurrentFwVersion())) {
                    lwM2MClient.getFrUpdate().setCurrentFwVersion((String) valueNew);
                }
            });
            log.info("[{}] delete [{}]  onAttributeUpdate", msg.getSharedDeletedList(), sessionInfo);
        }
    }

    /**
     * @param sessionInfo   -
     * @param deviceProfile -
     */
    @Override
    public void onDeviceProfileUpdate(SessionInfoProto sessionInfo, DeviceProfile deviceProfile) {
        Set<String> registrationIds = lwM2mClientContext.getLwM2mClients().entrySet()
                .stream()
                .filter(e -> e.getValue().getProfileId().equals(deviceProfile.getUuidId()))
                .map(Map.Entry::getKey).sorted().collect(Collectors.toCollection(LinkedHashSet::new));
        if (registrationIds.size() > 0) {
            this.onDeviceUpdateChangeProfile(registrationIds, deviceProfile);
        }
    }

    /**
     * @param sessionInfo      -
     * @param device           -
     * @param deviceProfileOpt -
     */
    @Override
    public void onDeviceUpdate(SessionInfoProto sessionInfo, Device device, Optional<DeviceProfile> deviceProfileOpt) {
        Optional<String> registrationIdOpt = lwM2mClientContext.getLwM2mClients().entrySet().stream()
                .filter(e -> device.getUuidId().equals(e.getValue().getDeviceId()))
                .map(Map.Entry::getKey)
                .findFirst();
        registrationIdOpt.ifPresent(registrationId -> this.onDeviceUpdateLwM2MClient(registrationId, device, deviceProfileOpt));
    }

    /**
     * @param resourceUpdateMsgOpt -
     */
    @Override
    public void onResourceUpdate(Optional<TransportProtos.ResourceUpdateMsg> resourceUpdateMsgOpt) {
        String idVer = resourceUpdateMsgOpt.get().getResourceKey();
        lwM2mClientContext.getLwM2mClients().values().stream().forEach(e -> e.updateResourceModel(idVer, this.config.getModelProvider()));
    }

    /**
     * @param resourceDeleteMsgOpt -
     */
    @Override
    public void onResourceDelete(Optional<TransportProtos.ResourceDeleteMsg> resourceDeleteMsgOpt) {
        String pathIdVer = resourceDeleteMsgOpt.get().getResourceKey();
        lwM2mClientContext.getLwM2mClients().values().stream().forEach(e -> e.deleteResources(pathIdVer, this.config.getModelProvider()));
    }

    @Override
    public void onToDeviceRpcRequest(TransportProtos.ToDeviceRpcRequestMsg toDeviceRequest, SessionInfoProto sessionInfo) {
        Lwm2mClientRpcRequest lwm2mClientRpcRequest = null;
        try {
            log.info("[{}] toDeviceRpcRequest", toDeviceRequest);
            Registration registration = lwM2mClientContext.getLwM2mClient(new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB())).getRegistration();
            lwm2mClientRpcRequest = this.getDeviceRpcRequest(toDeviceRequest, sessionInfo, registration);
            if (lwm2mClientRpcRequest != null && lwm2mClientRpcRequest.getErrorMsg() != null) {
                lwm2mClientRpcRequest.setResponseCode(BAD_REQUEST.name());
                this.onToDeviceRpcResponse(lwm2mClientRpcRequest.getDeviceRpcResponseResultMsg(), sessionInfo);
            } else {
                lwM2mTransportRequest.sendAllRequest(registration, lwm2mClientRpcRequest.getTargetIdVer(), lwm2mClientRpcRequest.getTypeOper(), lwm2mClientRpcRequest.getContentFormatName(),
                        lwm2mClientRpcRequest.getValue() == null ? lwm2mClientRpcRequest.getParams() : lwm2mClientRpcRequest.getValue(),
                        this.config.getTimeout(), lwm2mClientRpcRequest);
            }
        } catch (Exception e) {
            if (lwm2mClientRpcRequest == null) {
                lwm2mClientRpcRequest = new Lwm2mClientRpcRequest();
            }
            lwm2mClientRpcRequest.setResponseCode(BAD_REQUEST.name());
            if (lwm2mClientRpcRequest.getErrorMsg() == null) {
                lwm2mClientRpcRequest.setErrorMsg(e.getMessage());
            }
            this.onToDeviceRpcResponse(lwm2mClientRpcRequest.getDeviceRpcResponseResultMsg(), sessionInfo);
        }
    }

    /**
     * @param toDeviceRequest -
     * @param sessionInfo     -
     * @param registration    -
     * @return
     * @throws IllegalArgumentException
     */
    private Lwm2mClientRpcRequest getDeviceRpcRequest(TransportProtos.ToDeviceRpcRequestMsg toDeviceRequest,
                                                      SessionInfoProto sessionInfo, Registration registration) throws IllegalArgumentException {
        Lwm2mClientRpcRequest lwm2mClientRpcRequest = new Lwm2mClientRpcRequest();
        try {
            lwm2mClientRpcRequest.setRequestId(toDeviceRequest.getRequestId());
            lwm2mClientRpcRequest.setSessionInfo(sessionInfo);
            lwm2mClientRpcRequest.setValidTypeOper(toDeviceRequest.getMethodName());
            JsonObject rpcRequest = LwM2mTransportHandlerUtil.validateJson(toDeviceRequest.getParams());
            if (rpcRequest != null) {
                if (rpcRequest.has(lwm2mClientRpcRequest.keyNameKey)) {
                    String targetIdVer = this.getPresentPathIntoProfile(sessionInfo,
                            rpcRequest.get(lwm2mClientRpcRequest.keyNameKey).getAsString());
                    if (targetIdVer != null) {
                        lwm2mClientRpcRequest.setTargetIdVer(targetIdVer);
                        lwm2mClientRpcRequest.setInfoMsg(String.format("Changed by: key - %s, pathIdVer - %s",
                                rpcRequest.get(lwm2mClientRpcRequest.keyNameKey).getAsString(), targetIdVer));
                    }
                }
                if (lwm2mClientRpcRequest.getTargetIdVer() == null) {
                    lwm2mClientRpcRequest.setValidTargetIdVerKey(rpcRequest, registration);
                }
                if (rpcRequest.has(lwm2mClientRpcRequest.contentFormatNameKey)) {
                    lwm2mClientRpcRequest.setValidContentFormatName(rpcRequest);
                }
                if (rpcRequest.has(lwm2mClientRpcRequest.timeoutInMsKey) && rpcRequest.get(lwm2mClientRpcRequest.timeoutInMsKey).getAsLong() > 0) {
                    lwm2mClientRpcRequest.setTimeoutInMs(rpcRequest.get(lwm2mClientRpcRequest.timeoutInMsKey).getAsLong());
                }
                if (rpcRequest.has(lwm2mClientRpcRequest.valueKey)) {
                    lwm2mClientRpcRequest.setValue(rpcRequest.get(lwm2mClientRpcRequest.valueKey).getAsString());
                }
                if (rpcRequest.has(lwm2mClientRpcRequest.paramsKey) && rpcRequest.get(lwm2mClientRpcRequest.paramsKey).isJsonObject()) {
                    lwm2mClientRpcRequest.setParams(new Gson().fromJson(rpcRequest.get(lwm2mClientRpcRequest.paramsKey)
                            .getAsJsonObject().toString(), new TypeToken<ConcurrentHashMap<String, Object>>() {
                    }.getType()));
                }
                lwm2mClientRpcRequest.setSessionInfo(sessionInfo);
                if (OBSERVE_READ_ALL != lwm2mClientRpcRequest.getTypeOper() && lwm2mClientRpcRequest.getTargetIdVer() == null) {
                    lwm2mClientRpcRequest.setErrorMsg(lwm2mClientRpcRequest.targetIdVerKey + " and " +
                            lwm2mClientRpcRequest.keyNameKey + " is null or bad format");
                } else if ((EXECUTE == lwm2mClientRpcRequest.getTypeOper()
                        || WRITE_REPLACE == lwm2mClientRpcRequest.getTypeOper())
                        && lwm2mClientRpcRequest.getTargetIdVer() != null
                        && !(new LwM2mPath(convertPathFromIdVerToObjectId(lwm2mClientRpcRequest.getTargetIdVer())).isResource()
                        || new LwM2mPath(convertPathFromIdVerToObjectId(lwm2mClientRpcRequest.getTargetIdVer())).isResourceInstance())) {
                    lwm2mClientRpcRequest.setErrorMsg("Invalid parameter " + lwm2mClientRpcRequest.targetIdVerKey
                            + ". Only Resource or ResourceInstance can be this operation");
                } else if (WRITE_UPDATE == lwm2mClientRpcRequest.getTypeOper()) {
                    lwm2mClientRpcRequest.setErrorMsg("Procedures In Development...");
                }
            } else {
                lwm2mClientRpcRequest.setErrorMsg("Params of request is bad Json format.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(lwm2mClientRpcRequest.getErrorMsg());
        }
        return lwm2mClientRpcRequest;
    }

    public void sentRpcRequest(Lwm2mClientRpcRequest rpcRequest, String requestCode, String msg, String typeMsg) {
        rpcRequest.setResponseCode(requestCode);
        if (LOG_LW2M_ERROR.equals(typeMsg)) {
            rpcRequest.setInfoMsg(null);
            rpcRequest.setValueMsg(null);
            if (rpcRequest.getErrorMsg() == null) {
                msg = msg.isEmpty() ? null : msg;
                rpcRequest.setErrorMsg(msg);
            }
        } else if (LOG_LW2M_INFO.equals(typeMsg)) {
            if (rpcRequest.getInfoMsg() == null) {
                rpcRequest.setInfoMsg(msg);
            }
        } else if (LOG_LW2M_VALUE.equals(typeMsg)) {
            if (rpcRequest.getValueMsg() == null) {
                rpcRequest.setValueMsg(msg);
            }
        }
        this.onToDeviceRpcResponse(rpcRequest.getDeviceRpcResponseResultMsg(), rpcRequest.getSessionInfo());
    }

    @Override
    public void onToDeviceRpcResponse(TransportProtos.ToDeviceRpcResponseMsg toDeviceResponse, SessionInfoProto sessionInfo) {
        transportService.process(sessionInfo, toDeviceResponse, null);
    }

    public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg toServerResponse) {
        log.info("[{}] toServerRpcResponse", toServerResponse);
    }

    /**
     * Trigger Server path = "/1/0/8"
     * <p>
     * Trigger bootStrap path = "/1/0/9" - have to implemented on client
     */
    @Override
    public void doTrigger(Registration registration, String path) {
        lwM2mTransportRequest.sendAllRequest(registration, path, EXECUTE,
                ContentFormat.TLV.getName(), null, this.config.getTimeout(), null);
    }

    /**
     * Deregister session in transport
     *
     * @param sessionInfo - lwm2m client
     */
    @Override
    public void doDisconnect(SessionInfoProto sessionInfo) {
        transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.CLOSED), null);
        transportService.deregisterSession(sessionInfo);
    }

    /**
     * Session device in thingsboard is closed
     *
     * @param sessionInfo - lwm2m client
     */
    private void doCloseSession(SessionInfoProto sessionInfo) {
        TransportProtos.SessionEvent event = SessionEvent.CLOSED;
        TransportProtos.SessionEventMsg msg = TransportProtos.SessionEventMsg.newBuilder()
                .setSessionType(TransportProtos.SessionType.ASYNC)
                .setEvent(event).build();
        transportService.process(sessionInfo, msg, null);
    }

    /**
     * Those methods are called by the protocol stage thread pool, this means that execution MUST be done in a short delay,
     * * if you need to do long time processing use a dedicated thread pool.
     *
     * @param registration -
     */
    @Override
    public void onAwakeDev(Registration registration) {
        log.info("[{}] [{}] Received endpoint Awake version event", registration.getId(), registration.getEndpoint());
        this.sendLogsToThingsboard(LOG_LW2M_INFO + ": Client is awake!", registration.getId());
        //TODO: associate endpointId with device information.
    }

    /**
     * This method is used to sync with sessions
     * Removes a profile if not used in sessions
     */
    private void syncSessionsAndProfiles() {
        Map<UUID, LwM2mClientProfile> profilesClone = lwM2mClientContext.getProfiles().entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        profilesClone.forEach((k, v) -> {
            String registrationId = lwM2mClientContext.getLwM2mClients().entrySet()
                    .stream()
                    .filter(e -> e.getValue().getProfileId().equals(k))
                    .findFirst()
                    .map(Map.Entry::getKey) // return the key of the matching entry if found
                    .orElse("");
            if (registrationId.isEmpty()) {
                lwM2mClientContext.getProfiles().remove(k);
            }
        });
    }

    /**
     * @param logMsg       - text msg
     * @param registrationId - Id of Registration LwM2M Client
     */
    @Override
    public void sendLogsToThingsboard(String logMsg, String registrationId) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(registrationId);
        if (logMsg != null && sessionInfo != null) {
            if(logMsg.length() > 1024){
                logMsg = logMsg.substring(0, 1024);
            }
            this.helper.sendParametersOnThingsboardTelemetry(this.helper.getKvLogyToThingsboard(logMsg), sessionInfo);
        }
    }

    /**
     * #1 clientOnlyObserveAfterConnect == true
     * - Only Observe Request to the client marked as observe from the profile configuration.
     * #2. clientOnlyObserveAfterConnect == false
     * После регистрации отправляю запрос на read  всех ресурсов, которые после регистрации есть у клиента,
     * а затем запрос на observe (edited)
     * - Read Request to the client after registration to read all resource values for all objects
     * - then Observe Request to the client marked as observe from the profile configuration.
     *
     * @param registration - Registration LwM2M Client
     * @param lwM2MClient  - object with All parameters off client
     */
    private void initLwM2mFromClientValue(Registration registration, LwM2mClient lwM2MClient) {
        LwM2mClientProfile lwM2MClientProfile = lwM2mClientContext.getProfile(registration);
        Set<String> clientObjects = lwM2mClientContext.getSupportedIdVerInClient(registration);
        if (clientObjects != null && clientObjects.size() > 0) {
            if (LWM2M_STRATEGY_2 == LwM2mTransportHandlerUtil.getClientOnlyObserveAfterConnect(lwM2MClientProfile)) {
                // #2
                lwM2MClient.getPendingReadRequests().addAll(clientObjects);
                clientObjects.forEach(path -> lwM2mTransportRequest.sendAllRequest(registration, path, READ, ContentFormat.TLV.getName(),
                        null, this.config.getTimeout(), null));
            }
            // #1
            this.initReadAttrTelemetryObserveToClient(registration, lwM2MClient, READ, clientObjects);
            this.initReadAttrTelemetryObserveToClient(registration, lwM2MClient, OBSERVE, clientObjects);
            this.initReadAttrTelemetryObserveToClient(registration, lwM2MClient, WRITE_ATTRIBUTES, clientObjects);
            this.initReadAttrTelemetryObserveToClient(registration, lwM2MClient, DISCOVER, clientObjects);
        }
    }

    /**
     * @param registration -
     * @param lwM2mObject  -
     * @param path         -
     */
    private void updateObjectResourceValue(Registration registration, LwM2mObject lwM2mObject, String path) {
        LwM2mPath pathIds = new LwM2mPath(path);
        lwM2mObject.getInstances().forEach((instanceId, instance) -> {
            String pathInstance = pathIds.toString() + "/" + instanceId;
            this.updateObjectInstanceResourceValue(registration, instance, pathInstance);
        });
    }

    /**
     * @param registration        -
     * @param lwM2mObjectInstance -
     * @param path                -
     */
    private void updateObjectInstanceResourceValue(Registration registration, LwM2mObjectInstance lwM2mObjectInstance, String path) {
        LwM2mPath pathIds = new LwM2mPath(path);
        lwM2mObjectInstance.getResources().forEach((resourceId, resource) -> {
            String pathRez = pathIds.toString() + "/" + resourceId;
            this.updateResourcesValue(registration, resource, pathRez);
        });
    }

    /**
     * Sending observe value of resources to thingsboard
     * #1 Return old Value Resource from LwM2MClient
     * #2 Update new Resources (replace old Resource Value on new Resource Value)
     * #3 If fr_update -> UpdateFirmware
     * #4 updateAttrTelemetry
     *
     * @param registration  - Registration LwM2M Client
     * @param lwM2mResource - LwM2mSingleResource response.getContent()
     * @param path          - resource
     */
    private void updateResourcesValue(Registration registration, LwM2mResource lwM2mResource, String path) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClientWithReg(registration, null);
        if (lwM2MClient.saveResourceValue(path, lwM2mResource, this.config
                .getModelProvider())) {
            if (FR_PATH_RESOURCE_VER_ID.equals(convertPathFromIdVerToObjectId(path)) &&
                    lwM2MClient.getFrUpdate().getCurrentFwVersion() != null
                    && !lwM2MClient.getFrUpdate().getCurrentFwVersion().equals(lwM2MClient.getFrUpdate().getClientFwVersion())
                    && lwM2MClient.isUpdateFw()) {

                /** version != null
                 * set setClient_fw_version = value
                 **/
                lwM2MClient.setUpdateFw(false);
                lwM2MClient.getFrUpdate().setClientFwVersion(lwM2mResource.getValue().toString());
                log.warn("updateFirmwareClient3");
                this.updateFirmwareClient(lwM2MClient);
            }
            Set<String> paths = new HashSet<>();
            paths.add(path);
            this.updateAttrTelemetry(registration, paths);
        } else {
            log.error("Fail update Resource [{}]", lwM2mResource);
        }
    }


    /**
     * send Attribute and Telemetry to Thingsboard
     * #1 - get AttrName/TelemetryName with value from LwM2MClient:
     * -- resourceId == path from LwM2MClientProfile.postAttributeProfile/postTelemetryProfile/postObserveProfile
     * -- AttrName/TelemetryName == resourceName from ModelObject.objectModel, value from ModelObject.instance.resource(resourceId)
     * #2 - set Attribute/Telemetry
     *
     * @param registration - Registration LwM2M Client
     */
    private void updateAttrTelemetry(Registration registration, Set<String> paths) {
        try {
            ResultsAddKeyValueProto results = getParametersFromProfile(registration, paths);
            SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration);
            if (results != null && sessionInfo != null) {
                if (results.getResultAttributes().size() > 0) {
                    this.helper.sendParametersOnThingsboardAttribute(results.getResultAttributes(), sessionInfo);
                }
                if (results.getResultTelemetries().size() > 0) {
                    this.helper.sendParametersOnThingsboardTelemetry(results.getResultTelemetries(), sessionInfo);
                }
            }
        } catch (Exception e) {
            log.error("UpdateAttrTelemetry", e);
        }
    }

    /**
     * Start observe/read: Attr/Telemetry
     * #1 - Analyze: path in resource profile == client resource
     *
     * @param registration -
     */
    private void initReadAttrTelemetryObserveToClient(Registration registration, LwM2mClient lwM2MClient,
                                                      LwM2mTypeOper typeOper, Set<String> clientObjects) {
        LwM2mClientProfile lwM2MClientProfile = lwM2mClientContext.getProfile(registration);
        Set<String> result = null;
        ConcurrentHashMap<String, Object> params = null;
        if (READ.equals(typeOper)) {
            result = JacksonUtil.fromString(lwM2MClientProfile.getPostAttributeProfile().toString(),
                    new TypeReference<>() {
                    });
            result.addAll(JacksonUtil.fromString(lwM2MClientProfile.getPostTelemetryProfile().toString(),
                    new TypeReference<>() {
                    }));
        } else if (OBSERVE.equals(typeOper)) {
            result = JacksonUtil.fromString(lwM2MClientProfile.getPostObserveProfile().toString(),
                    new TypeReference<>() {
                    });
        } else if (DISCOVER.equals(typeOper)) {
            result = this.getPathForWriteAttributes(lwM2MClientProfile.getPostAttributeLwm2mProfile()).keySet();
        } else if (WRITE_ATTRIBUTES.equals(typeOper)) {
            params = this.getPathForWriteAttributes(lwM2MClientProfile.getPostAttributeLwm2mProfile());
            result = params.keySet();
        }
        if (result != null && !result.isEmpty()) {
            // #1
            Set<String> pathSend = result.stream().filter(target -> {
                        return target.split(LWM2M_SEPARATOR_PATH).length < 3 ?
                                clientObjects.contains("/" + target.split(LWM2M_SEPARATOR_PATH)[1]) :
                                clientObjects.contains("/" + target.split(LWM2M_SEPARATOR_PATH)[1] + "/" + target.split(LWM2M_SEPARATOR_PATH)[2]);
                    }
            ).collect(Collectors.toUnmodifiableSet());
            if (!pathSend.isEmpty()) {
                lwM2MClient.getPendingReadRequests().addAll(pathSend);
                ConcurrentHashMap<String, Object> finalParams = params;
                pathSend.forEach(target -> {
                        lwM2mTransportRequest.sendAllRequest(registration, target, typeOper, ContentFormat.TLV.getName(),
                                finalParams != null ? finalParams.get(target) : null, this.config.getTimeout(), null);
                });
                if (OBSERVE.equals(typeOper)) {
                    lwM2MClient.initReadValue(this, null);
                }
            }
        }
    }

    private ConcurrentHashMap<String, Object> getPathForWriteAttributes(JsonObject objectJson) {
        ConcurrentHashMap<String, Object> pathAttributes = new Gson().fromJson(objectJson.toString(),
                new TypeToken<ConcurrentHashMap<String, Object>>() {
                }.getType());
        return pathAttributes;
    }

    /**
     * Update parameters device in LwM2MClient
     * If new deviceProfile != old deviceProfile => update deviceProfile
     *
     * @param registrationId -
     * @param device         -
     */
    private void onDeviceUpdateLwM2MClient(String registrationId, Device device, Optional<DeviceProfile> deviceProfileOpt) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClients().get(registrationId);
        lwM2MClient.setDeviceName(device.getName());
        if (!lwM2MClient.getProfileId().equals(device.getDeviceProfileId().getId())) {
            Set<String> registrationIds = new HashSet<>();
            registrationIds.add(registrationId);
            deviceProfileOpt.ifPresent(deviceProfile -> this.onDeviceUpdateChangeProfile(registrationIds, deviceProfile));
        }

        lwM2MClient.setProfileId(device.getDeviceProfileId().getId());
    }

    /**
     * //     * @param attributes   - new JsonObject
     * //     * @param telemetry    - new JsonObject
     *
     * @param registration - Registration LwM2M Client
     * @param path         -
     */
    private ResultsAddKeyValueProto getParametersFromProfile(Registration registration, Set<String> path) {
        if (path != null && path.size() > 0) {
            ResultsAddKeyValueProto results = new ResultsAddKeyValueProto();
            LwM2mClientProfile lwM2MClientProfile = lwM2mClientContext.getProfile(registration);
            List<TransportProtos.KeyValueProto> resultAttributes = new ArrayList<>();
            lwM2MClientProfile.getPostAttributeProfile().forEach(pathIdVer -> {
                if (path.contains(pathIdVer.getAsString())) {
                    TransportProtos.KeyValueProto kvAttr = this.getKvToThingsboard(pathIdVer.getAsString(), registration);
                    if (kvAttr != null) {
                        resultAttributes.add(kvAttr);
                    }
                }
            });
            List<TransportProtos.KeyValueProto> resultTelemetries = new ArrayList<>();
            lwM2MClientProfile.getPostTelemetryProfile().forEach(pathIdVer -> {
                if (path.contains(pathIdVer.getAsString())) {
                    TransportProtos.KeyValueProto kvAttr = this.getKvToThingsboard(pathIdVer.getAsString(), registration);
                    if (kvAttr != null) {
                        resultTelemetries.add(kvAttr);
                    }
                }
            });
            if (resultAttributes.size() > 0) {
                results.setResultAttributes(resultAttributes);
            }
            if (resultTelemetries.size() > 0) {
                results.setResultTelemetries(resultTelemetries);
            }
            return results;
        }
        return null;
    }

    private TransportProtos.KeyValueProto getKvToThingsboard(String pathIdVer, Registration registration) {
        LwM2mClient lwM2MClient = this.lwM2mClientContext.getLwM2mClientWithReg(null, registration.getId());
        JsonObject names = lwM2mClientContext.getProfiles().get(lwM2MClient.getProfileId()).getPostKeyNameProfile();
        if (names != null && names.has(pathIdVer)) {
            String resourceName = names.get(pathIdVer).getAsString();
            if (resourceName != null && !resourceName.isEmpty()) {
                try {
                    LwM2mResource resourceValue = lwM2MClient != null ? getResourceValueFromLwM2MClient(lwM2MClient, pathIdVer) : null;
                    if (resourceValue != null) {
                        ResourceModel.Type currentType = resourceValue.getType();
                        ResourceModel.Type expectedType = this.helper.getResourceModelTypeEqualsKvProtoValueType(currentType, pathIdVer);
                        Object valueKvProto = null;
                        if (resourceValue.isMultiInstances()) {
                            valueKvProto = new JsonObject();
                            Object finalvalueKvProto = valueKvProto;
                            Gson gson = new GsonBuilder().create();
                            resourceValue.getValues().forEach((k, v) -> {
                                Object val = this.converter.convertValue(resourceValue.getValue(), currentType, expectedType,
                                        new LwM2mPath(convertPathFromIdVerToObjectId(pathIdVer)));
                                JsonElement element = gson.toJsonTree(val, val.getClass());
                                ((JsonObject) finalvalueKvProto).add(String.valueOf(k), element);
                            });
                            valueKvProto = gson.toJson(valueKvProto);
                        } else {
                            valueKvProto = this.converter.convertValue(resourceValue.getValue(), currentType, expectedType,
                                    new LwM2mPath(convertPathFromIdVerToObjectId(pathIdVer)));
                        }
                        return valueKvProto != null ? this.helper.getKvAttrTelemetryToThingsboard(currentType, resourceName, valueKvProto, resourceValue.isMultiInstances()) : null;
                    }
                } catch (Exception e) {
                    log.error("Failed to add parameters.", e);
                }
            }
        } else {
            log.error("Failed to add parameters. path: [{}], names: [{}]", pathIdVer, names);
        }
        return null;
    }

    /**
     * @param pathIdVer - path resource
     * @return - value of Resource into format KvProto or null
     */
    private Object getResourceValueFormatKv(LwM2mClient lwM2MClient, String pathIdVer) {
        LwM2mResource resourceValue = this.getResourceValueFromLwM2MClient(lwM2MClient, pathIdVer);
        ResourceModel.Type currentType = resourceValue.getType();
        ResourceModel.Type expectedType = this.helper.getResourceModelTypeEqualsKvProtoValueType(currentType, pathIdVer);
        return this.converter.convertValue(resourceValue.getValue(), currentType, expectedType,
                new LwM2mPath(convertPathFromIdVerToObjectId(pathIdVer)));
    }

    /**
     * @param lwM2MClient -
     * @param path        -
     * @return - return value of Resource by idPath
     */
    private LwM2mResource getResourceValueFromLwM2MClient(LwM2mClient lwM2MClient, String path) {
        LwM2mResource resourceValue = null;
        if (new LwM2mPath(convertPathFromIdVerToObjectId(path)).isResource()) {
            resourceValue = lwM2MClient.getResources().get(path).getLwM2mResource();
        }
        return resourceValue;
    }

    /**
     * Update resource (attribute) value  on thingsboard after update value in client
     *
     * @param registration -
     * @param path         -
     * @param request      -
     */
    public void onWriteResponseOk(Registration registration, String path, WriteRequest request) {
        this.updateResourcesValue(registration, ((LwM2mResource) request.getNode()), path);
    }

    /**
     * #1 Read new, old Value (Attribute, Telemetry, Observe, KeyName)
     * #2 Update in lwM2MClient: ...Profile if changes from update device
     * #3 Equivalence test: old <> new Value (Attribute, Telemetry, Observe, KeyName)
     * #3.1 Attribute isChange (add&del)
     * #3.2 Telemetry isChange (add&del)
     * #3.3 KeyName isChange (add)
     * #3.4 attributeLwm2m isChange (update WrightAttribute: add/update/del)
     * #4 update
     * #4.1 add If #3 isChange, then analyze and update Value in Transport form Client and send Value to thingsboard
     * #4.2 del
     * -- if  add attributes includes del telemetry - result del for observe
     * #5
     * #5.1 Observe isChange (add&del)
     * #5.2 Observe.add
     * -- path Attr/Telemetry includes newObserve and does not include oldObserve: send Request observe to Client
     * #5.3 Observe.del
     * -- different between newObserve and oldObserve: send Request cancel observe to client
     * #6
     * #6.1 - update WriteAttribute
     * #6.2 - del WriteAttribute
     *
     * @param registrationIds -
     * @param deviceProfile   -
     */
    private void onDeviceUpdateChangeProfile(Set<String> registrationIds, DeviceProfile deviceProfile) {
        LwM2mClientProfile lwM2MClientProfileOld = lwM2mClientContext.getProfiles().get(deviceProfile.getUuidId()).clone();
        if (lwM2mClientContext.addUpdateProfileParameters(deviceProfile)) {
            // #1
            JsonArray attributeOld = lwM2MClientProfileOld.getPostAttributeProfile();
            Set<String> attributeSetOld = convertJsonArrayToSet(attributeOld);
            JsonArray telemetryOld = lwM2MClientProfileOld.getPostTelemetryProfile();
            Set<String> telemetrySetOld = convertJsonArrayToSet(telemetryOld);
            JsonArray observeOld = lwM2MClientProfileOld.getPostObserveProfile();
            JsonObject keyNameOld = lwM2MClientProfileOld.getPostKeyNameProfile();
            JsonObject attributeLwm2mOld = lwM2MClientProfileOld.getPostAttributeLwm2mProfile();

            LwM2mClientProfile lwM2MClientProfileNew = lwM2mClientContext.getProfiles().get(deviceProfile.getUuidId());
            JsonArray attributeNew = lwM2MClientProfileNew.getPostAttributeProfile();
            Set<String> attributeSetNew = convertJsonArrayToSet(attributeNew);
            JsonArray telemetryNew = lwM2MClientProfileNew.getPostTelemetryProfile();
            Set<String> telemetrySetNew = convertJsonArrayToSet(telemetryNew);
            JsonArray observeNew = lwM2MClientProfileNew.getPostObserveProfile();
            JsonObject keyNameNew = lwM2MClientProfileNew.getPostKeyNameProfile();
            JsonObject attributeLwm2mNew = lwM2MClientProfileNew.getPostAttributeLwm2mProfile();

            // #3
            ResultsAnalyzerParameters sendAttrToThingsboard = new ResultsAnalyzerParameters();
            // #3.1
            if (!attributeOld.equals(attributeNew)) {
                ResultsAnalyzerParameters postAttributeAnalyzer = this.getAnalyzerParameters(new Gson().fromJson(attributeOld,
                        new TypeToken<Set<String>>() {
                        }.getType()), attributeSetNew);
                sendAttrToThingsboard.getPathPostParametersAdd().addAll(postAttributeAnalyzer.getPathPostParametersAdd());
                sendAttrToThingsboard.getPathPostParametersDel().addAll(postAttributeAnalyzer.getPathPostParametersDel());
            }
            // #3.2
            if (!telemetryOld.equals(telemetryNew)) {
                ResultsAnalyzerParameters postTelemetryAnalyzer = this.getAnalyzerParameters(new Gson().fromJson(telemetryOld,
                        new TypeToken<Set<String>>() {
                        }.getType()), telemetrySetNew);
                sendAttrToThingsboard.getPathPostParametersAdd().addAll(postTelemetryAnalyzer.getPathPostParametersAdd());
                sendAttrToThingsboard.getPathPostParametersDel().addAll(postTelemetryAnalyzer.getPathPostParametersDel());
            }
            // #3.3
            if (!keyNameOld.equals(keyNameNew)) {
                ResultsAnalyzerParameters keyNameChange = this.getAnalyzerKeyName(new Gson().fromJson(keyNameOld.toString(),
                        new TypeToken<ConcurrentHashMap<String, String>>() {
                        }.getType()),
                        new Gson().fromJson(keyNameNew.toString(), new TypeToken<ConcurrentHashMap<String, String>>() {
                        }.getType()));
                sendAttrToThingsboard.getPathPostParametersAdd().addAll(keyNameChange.getPathPostParametersAdd());
            }

            // #3.4, #6
            if (!attributeLwm2mOld.equals(attributeLwm2mNew)) {
                this.getAnalyzerAttributeLwm2m(registrationIds, attributeLwm2mOld, attributeLwm2mNew);
            }

            // #4.1 add
            if (sendAttrToThingsboard.getPathPostParametersAdd().size() > 0) {
                // update value in Resources
                registrationIds.forEach(registrationId -> {
                    Registration registration = lwM2mClientContext.getRegistration(registrationId);
                    this.readObserveFromProfile(registration, sendAttrToThingsboard.getPathPostParametersAdd(), READ);
                    // send attr/telemetry to tingsboard for new path
                    this.updateAttrTelemetry(registration, sendAttrToThingsboard.getPathPostParametersAdd());
                });
            }
            // #4.2 del
            if (sendAttrToThingsboard.getPathPostParametersDel().size() > 0) {
                ResultsAnalyzerParameters sendAttrToThingsboardDel = this.getAnalyzerParameters(sendAttrToThingsboard.getPathPostParametersAdd(), sendAttrToThingsboard.getPathPostParametersDel());
                sendAttrToThingsboard.setPathPostParametersDel(sendAttrToThingsboardDel.getPathPostParametersDel());
            }

            // #5.1
            if (!observeOld.equals(observeNew)) {
                Set<String> observeSetOld = new Gson().fromJson(observeOld, new TypeToken<Set<String>>() {
                }.getType());
                Set<String> observeSetNew = new Gson().fromJson(observeNew, new TypeToken<Set<String>>() {
                }.getType());
                //#5.2 add
                //  path Attr/Telemetry includes newObserve
                attributeSetOld.addAll(telemetrySetOld);
                ResultsAnalyzerParameters sendObserveToClientOld = this.getAnalyzerParametersIn(attributeSetOld, observeSetOld); // add observe
                attributeSetNew.addAll(telemetrySetNew);
                ResultsAnalyzerParameters sendObserveToClientNew = this.getAnalyzerParametersIn(attributeSetNew, observeSetNew); // add observe
                // does not include oldObserve
                ResultsAnalyzerParameters postObserveAnalyzer = this.getAnalyzerParameters(sendObserveToClientOld.getPathPostParametersAdd(), sendObserveToClientNew.getPathPostParametersAdd());
                //  send Request observe to Client
                registrationIds.forEach(registrationId -> {
                    Registration registration = lwM2mClientContext.getRegistration(registrationId);
                    if (postObserveAnalyzer.getPathPostParametersAdd().size() > 0) {
                        this.readObserveFromProfile(registration, postObserveAnalyzer.getPathPostParametersAdd(), OBSERVE);
                    }
                    // 5.3 del
                    //  send Request cancel observe to Client
                    if (postObserveAnalyzer.getPathPostParametersDel().size() > 0) {
                        this.cancelObserveFromProfile(registration, postObserveAnalyzer.getPathPostParametersDel());
                    }
                });
            }
        }
    }

    /**
     * Compare old list with new list  after change AttrTelemetryObserve in config Profile
     *
     * @param parametersOld -
     * @param parametersNew -
     * @return ResultsAnalyzerParameters: add && new
     */
    private ResultsAnalyzerParameters getAnalyzerParameters(Set<String> parametersOld, Set<String> parametersNew) {
        ResultsAnalyzerParameters analyzerParameters = null;
        if (!parametersOld.equals(parametersNew)) {
            analyzerParameters = new ResultsAnalyzerParameters();
            analyzerParameters.setPathPostParametersAdd(parametersNew
                    .stream().filter(p -> !parametersOld.contains(p)).collect(Collectors.toSet()));
            analyzerParameters.setPathPostParametersDel(parametersOld
                    .stream().filter(p -> !parametersNew.contains(p)).collect(Collectors.toSet()));
        }
        return analyzerParameters;
    }

    private ResultsAnalyzerParameters getAnalyzerParametersIn(Set<String> parametersObserve, Set<String> parameters) {
        ResultsAnalyzerParameters analyzerParameters = new ResultsAnalyzerParameters();
        analyzerParameters.setPathPostParametersAdd(parametersObserve
                .stream().filter(parameters::contains).collect(Collectors.toSet()));
        return analyzerParameters;
    }

    /**
     * Update Resource value after change RezAttrTelemetry in config Profile
     * send response Read to Client and add path to pathResAttrTelemetry in LwM2MClient.getAttrTelemetryObserveValue()
     *
     * @param registration - Registration LwM2M Client
     * @param targets      - path Resources == [ "/2/0/0", "/2/0/1"]
     */
    private void readObserveFromProfile(Registration registration, Set<String> targets, LwM2mTypeOper typeOper) {
        targets.forEach(target -> {
            LwM2mPath pathIds = new LwM2mPath(convertPathFromIdVerToObjectId(target));
            if (pathIds.isResource()) {
                if (READ.equals(typeOper)) {
                    lwM2mTransportRequest.sendAllRequest(registration, target, typeOper,
                            ContentFormat.TLV.getName(), null, this.config.getTimeout(), null);
                } else if (OBSERVE.equals(typeOper)) {
                    lwM2mTransportRequest.sendAllRequest(registration, target, typeOper,
                            null, null, this.config.getTimeout(), null);
                }
            }
        });
    }

    private ResultsAnalyzerParameters getAnalyzerKeyName(ConcurrentHashMap<String, String> keyNameOld, ConcurrentHashMap<String, String> keyNameNew) {
        ResultsAnalyzerParameters analyzerParameters = new ResultsAnalyzerParameters();
        Set<String> paths = keyNameNew.entrySet()
                .stream()
                .filter(e -> !e.getValue().equals(keyNameOld.get(e.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).keySet();
        analyzerParameters.setPathPostParametersAdd(paths);
        return analyzerParameters;
    }

    /**
     * #3.4, #6
     * #6
     * #6.1 - send update WriteAttribute
     * #6.2 - send empty WriteAttribute
     *
     * @param attributeLwm2mOld -
     * @param attributeLwm2mNew -
     * @return
     */
    private void getAnalyzerAttributeLwm2m(Set<String> registrationIds, JsonObject attributeLwm2mOld, JsonObject attributeLwm2mNew) {
        ResultsAnalyzerParameters analyzerParameters = new ResultsAnalyzerParameters();
        ConcurrentHashMap<String, Object> lwm2mAttributesOld = new Gson().fromJson(attributeLwm2mOld.toString(),
                new TypeToken<ConcurrentHashMap<String, Object>>() {
                }.getType());
        ConcurrentHashMap<String, Object> lwm2mAttributesNew = new Gson().fromJson(attributeLwm2mNew.toString(),
                new TypeToken<ConcurrentHashMap<String, Object>>() {
                }.getType());
        Set<String> pathOld = lwm2mAttributesOld.keySet();
        Set<String> pathNew = lwm2mAttributesNew.keySet();
        analyzerParameters.setPathPostParametersAdd(pathNew
                .stream().filter(p -> !pathOld.contains(p)).collect(Collectors.toSet()));
        analyzerParameters.setPathPostParametersDel(pathOld
                .stream().filter(p -> !pathNew.contains(p)).collect(Collectors.toSet()));
        Set<String> pathCommon = pathNew
                .stream().filter(p -> pathOld.contains(p)).collect(Collectors.toSet());
        Set<String> pathCommonChange = pathCommon
                .stream().filter(p -> !lwm2mAttributesOld.get(p).equals(lwm2mAttributesNew.get(p))).collect(Collectors.toSet());
        analyzerParameters.getPathPostParametersAdd().addAll(pathCommonChange);
        // #6
        // #6.2
        if (analyzerParameters.getPathPostParametersAdd().size() > 0) {
            registrationIds.forEach(registrationId -> {
                Registration registration = this.lwM2mClientContext.getRegistration(registrationId);
                Set<String> clientObjects = lwM2mClientContext.getSupportedIdVerInClient(registration);
                Set<String> pathSend = analyzerParameters.getPathPostParametersAdd().stream().filter(target -> clientObjects.contains("/" + target.split(LWM2M_SEPARATOR_PATH)[1]))
                        .collect(Collectors.toUnmodifiableSet());
                if (!pathSend.isEmpty()) {
                    ConcurrentHashMap<String, Object> finalParams = lwm2mAttributesNew;
                    pathSend.forEach(target -> lwM2mTransportRequest.sendAllRequest(registration, target, WRITE_ATTRIBUTES, ContentFormat.TLV.getName(),
                            finalParams.get(target), this.config.getTimeout(), null));
                }
            });
        }
        // #6.2
        if (analyzerParameters.getPathPostParametersDel().size() > 0) {
            registrationIds.forEach(registrationId -> {
                Registration registration = this.lwM2mClientContext.getRegistration(registrationId);
                Set<String> clientObjects = lwM2mClientContext.getSupportedIdVerInClient(registration);
                Set<String> pathSend = analyzerParameters.getPathPostParametersDel().stream().filter(target -> clientObjects.contains("/" + target.split(LWM2M_SEPARATOR_PATH)[1]))
                        .collect(Collectors.toUnmodifiableSet());
                if (!pathSend.isEmpty()) {
                    pathSend.forEach(target -> {
                        Map<String, Object> params = (Map<String, Object>) lwm2mAttributesOld.get(target);
                        params.clear();
                        params.put(OBJECT_VERSION, "");
                        lwM2mTransportRequest.sendAllRequest(registration, target, WRITE_ATTRIBUTES, ContentFormat.TLV.getName(),
                                params, this.config.getTimeout(), null);
                    });
                }
            });
        }

    }

    private void cancelObserveFromProfile(Registration registration, Set<String> paramAnallyzer) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClientWithReg(registration, null);
        paramAnallyzer.forEach(pathIdVer -> {
                    if (this.getResourceValueFromLwM2MClient(lwM2MClient, pathIdVer) != null) {
                        lwM2mTransportRequest.sendAllRequest(registration, pathIdVer, OBSERVE_CANCEL, null,
                                null, this.config.getTimeout(), null);
                    }
                }
        );
    }

    private void updateResourcesValueToClient(LwM2mClient lwM2MClient, Object valueOld, Object valueNew, String path) {
        if (valueNew != null && (valueOld == null || !valueNew.toString().equals(valueOld.toString()))) {
            lwM2mTransportRequest.sendAllRequest(lwM2MClient.getRegistration(), path, WRITE_REPLACE,
                    ContentFormat.TLV.getName(), valueNew,
                    this.config.getTimeout(), null);
        } else {
            log.error("Failed update resource [{}] [{}]", path, valueNew);
            String logMsg = String.format("%s: Failed update resource path - %s value - %s. Value is not changed or bad",
                    LOG_LW2M_ERROR, path, valueNew);
            this.sendLogsToThingsboard(logMsg, lwM2MClient.getRegistration().getId());
            log.info("Failed update resource [{}] [{}]", path, valueNew);
        }
    }

    /**
     * @param updateCredentials - Credentials include config only security Client (without config attr/telemetry...)
     *                          config attr/telemetry... in profile
     */
    public void onToTransportUpdateCredentials(TransportProtos.ToTransportUpdateCredentialsProto updateCredentials) {
        log.info("[{}] idList [{}] valueList updateCredentials", updateCredentials.getCredentialsIdList(), updateCredentials.getCredentialsValueList());
    }

    /**
     * Get path to resource from profile equal keyName
     *
     * @param sessionInfo -
     * @param name        -
     * @return -
     */
    private String getPresentPathIntoProfile(TransportProtos.SessionInfoProto sessionInfo, String name) {
        LwM2mClientProfile profile = lwM2mClientContext.getProfile(new UUID(sessionInfo.getDeviceProfileIdMSB(), sessionInfo.getDeviceProfileIdLSB()));
        LwM2mClient lwM2mClient = lwM2mClientContext.getLwM2MClient(sessionInfo);
        return profile.getPostKeyNameProfile().getAsJsonObject().entrySet().stream()
                .filter(e -> e.getValue().getAsString().equals(name) && validateResourceInModel(lwM2mClient, e.getKey(), false)).findFirst().map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 1. FirmwareUpdate:
     * - msg.getSharedUpdatedList().forEach(tsKvProto -> {tsKvProto.getKv().getKey().indexOf(FIRMWARE_UPDATE_PREFIX, 0) == 0
     * 2. Update resource value on client: if there is a difference in values between the current resource values and the shared attribute values
     * - Get path resource by result attributesResponse
     *
     * @param attributesResponse -
     * @param sessionInfo        -
     */
    public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg attributesResponse, TransportProtos.SessionInfoProto sessionInfo) {
        try {
            List<TransportProtos.TsKvProto> tsKvProtos = attributesResponse.getSharedAttributeListList();

            this.updateAttriuteFromThingsboard(tsKvProtos, sessionInfo);
        } catch (Exception e) {
            log.error(String.valueOf(e));
        }
    }

    /**
     * #1.1 If two names have equal path => last time attribute
     * #2.1 if there is a difference in values between the current resource values and the shared attribute values
     * => send to client Request Update of value (new value from shared attribute)
     * and LwM2MClient.delayedRequests.add(path)
     * #2.1 if there is not a difference in values between the current resource values and the shared attribute values
     *
     * @param tsKvProtos
     * @param sessionInfo
     */
    public void updateAttriuteFromThingsboard(List<TransportProtos.TsKvProto> tsKvProtos, TransportProtos.SessionInfoProto sessionInfo) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2MClient(sessionInfo);
        tsKvProtos.forEach(tsKvProto -> {
            String pathIdVer = this.getPresentPathIntoProfile(sessionInfo, tsKvProto.getKv().getKey());
            if (pathIdVer != null) {
                // #1.1
                if (lwM2MClient.getDelayedRequests().containsKey(pathIdVer) && tsKvProto.getTs() > lwM2MClient.getDelayedRequests().get(pathIdVer).getTs()) {
                    lwM2MClient.getDelayedRequests().put(pathIdVer, tsKvProto);
                } else if (!lwM2MClient.getDelayedRequests().containsKey(pathIdVer)) {
                    lwM2MClient.getDelayedRequests().put(pathIdVer, tsKvProto);
                }
            }
        });
        // #2.1
        lwM2MClient.getDelayedRequests().forEach((pathIdVer, tsKvProto) -> {
            this.updateResourcesValueToClient(lwM2MClient, this.getResourceValueFormatKv(lwM2MClient, pathIdVer),
                    this.helper.getValueFromKvProto(tsKvProto.getKv()), pathIdVer);
        });
    }

    /**
     * @param lwM2MClient -
     * @return SessionInfoProto -
     */
    private SessionInfoProto getNewSessionInfoProto(LwM2mClient lwM2MClient) {
        if (lwM2MClient != null) {
            TransportProtos.ValidateDeviceCredentialsResponseMsg msg = lwM2MClient.getCredentialsResponse();
            if (msg == null) {
                log.error("[{}] [{}]", lwM2MClient.getEndpoint(), CLIENT_NOT_AUTHORIZED);
                this.closeClientSession(lwM2MClient.getRegistration());
                return null;
            } else {
                return SessionInfoProto.newBuilder()
                        .setNodeId(this.context.getNodeId())
                        .setSessionIdMSB(lwM2MClient.getSessionId().getMostSignificantBits())
                        .setSessionIdLSB(lwM2MClient.getSessionId().getLeastSignificantBits())
                        .setDeviceIdMSB(msg.getDeviceInfo().getDeviceIdMSB())
                        .setDeviceIdLSB(msg.getDeviceInfo().getDeviceIdLSB())
                        .setTenantIdMSB(msg.getDeviceInfo().getTenantIdMSB())
                        .setTenantIdLSB(msg.getDeviceInfo().getTenantIdLSB())
                        .setCustomerIdMSB(msg.getDeviceInfo().getCustomerIdMSB())
                        .setCustomerIdLSB(msg.getDeviceInfo().getCustomerIdLSB())
                        .setDeviceName(msg.getDeviceInfo().getDeviceName())
                        .setDeviceType(msg.getDeviceInfo().getDeviceType())
                        .setDeviceProfileIdLSB(msg.getDeviceInfo().getDeviceProfileIdLSB())
                        .setDeviceProfileIdMSB(msg.getDeviceInfo().getDeviceProfileIdMSB())
                        .build();
            }
        }
        return null;
    }

    /**
     * @param registration - Registration LwM2M Client
     * @return - sessionInfo after access connect client
     */
    private SessionInfoProto getValidateSessionInfo(Registration registration) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClientWithReg(registration, null);
        return getNewSessionInfoProto(lwM2MClient);
    }

    /**
     * @param registrationId -
     * @return -
     */
    private SessionInfoProto getValidateSessionInfo(String registrationId) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClientWithReg(null, registrationId);
        return lwM2MClient != null ? this.getNewSessionInfoProto(lwM2MClient) : null;
    }

    /**
     * if sessionInfo removed from sessions, then new registerAsyncSession
     *
     * @param sessionInfo -
     */
    private void checkInactivity(SessionInfoProto sessionInfo) {
        if (transportService.reportActivity(sessionInfo) == null) {
            transportService.registerAsyncSession(sessionInfo, new LwM2mSessionMsgListener(this, sessionInfo));
        }
    }

    private void checkInactivityAndReportActivity() {
        lwM2mClientContext.getLwM2mClients().forEach((key, value) -> this.checkInactivity(this.getValidateSessionInfo(key)));
    }

    /**
     * #1. !!!  sharedAttr === profileAttr  !!!
     * - If there is a difference in values between the current resource values and the shared attribute values
     * - when the client connects to the server
     * #1.1 get attributes name from profile include name resources in ModelObject if resource  isWritable
     * #1.2 #1 size > 0 => send Request getAttributes to thingsboard
     * #2. FirmwareAttribute subscribe:
     *
     * @param lwM2MClient - LwM2M Client
     */
    public void putDelayedUpdateResourcesThingsboard(LwM2mClient lwM2MClient) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(lwM2MClient.getRegistration());
        if (sessionInfo != null) {
            //#1.1
            ConcurrentMap<String, String> keyNamesMap = this.getNamesFromProfileForSharedAttributes(lwM2MClient);
            if (keyNamesMap.values().size() > 0) {
                try {
                    //#1.2
                    TransportProtos.GetAttributeRequestMsg getAttributeMsg = helper.getAdaptor().convertToGetAttributes(null, keyNamesMap.values());
                    transportService.process(sessionInfo, getAttributeMsg, getAckCallback(lwM2MClient, getAttributeMsg.getRequestId(), DEVICE_ATTRIBUTES_REQUEST));
                } catch (AdaptorException e) {
                    log.warn("Failed to decode get attributes request", e);
                }
            }

        }
    }

    public void getInfoFirmwareUpdate(LwM2mClient lwM2MClient) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(lwM2MClient.getRegistration());
        if (sessionInfo != null) {
            TransportProtos.GetFirmwareRequestMsg getFirmwareRequestMsg = TransportProtos.GetFirmwareRequestMsg.newBuilder()
                    .setDeviceIdMSB(sessionInfo.getDeviceIdMSB())
                    .setDeviceIdLSB(sessionInfo.getDeviceIdLSB())
                    .setTenantIdMSB(sessionInfo.getTenantIdMSB())
                    .setTenantIdLSB(sessionInfo.getTenantIdLSB())
                    .setType(FirmwareType.FIRMWARE.name())
                    .build();
            transportService.process(sessionInfo, getFirmwareRequestMsg,
                    new TransportServiceCallback<>() {
                        @Override
                        public void onSuccess(TransportProtos.GetFirmwareResponseMsg response) {
                            if (TransportProtos.ResponseStatus.SUCCESS.equals(response.getResponseStatus())) {
                                lwM2MClient.getFrUpdate().setCurrentFwVersion(response.getVersion());
                                lwM2MClient.getFrUpdate().setCurrentFwId(new FirmwareId(new UUID(response.getFirmwareIdMSB(), response.getFirmwareIdLSB())).getId());
                                lwM2MClient.setUpdateFw(true);
                                readRequestToClientFirmwareVer(lwM2MClient.getRegistration());
                            } else {
                                log.trace("Firmware [{}] [{}]", lwM2MClient.getDeviceName(), response.getResponseStatus().toString());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.trace("Failed to process credentials ", e);
                        }
                    });
        }
    }

    /**
     * @param registration
     */
    public void readRequestToClientFirmwareVer(Registration registration) {
        String pathIdVer = convertPathFromObjectIdToIdVer(FR_PATH_RESOURCE_VER_ID, registration);
        lwM2mTransportRequest.sendAllRequest(registration, pathIdVer, READ, ContentFormat.TLV.getName(),
                null, config.getTimeout(), null);
    }

    /**
     *
     * @param lwM2MClient -
     */
    public void updateFirmwareClient(LwM2mClient lwM2MClient) {
        if (!lwM2MClient.getFrUpdate().getCurrentFwVersion().equals(lwM2MClient.getFrUpdate().getClientFwVersion())) {
            int chunkSize = 0;
            int chunk = 0;
            byte[] firmwareChunk = firmwareDataCache.get(lwM2MClient.getFrUpdate().getCurrentFwId().toString(), chunkSize, chunk);
            String verSupportedObject = lwM2MClient.getRegistration().getSupportedObject().get(FR_OBJECT_ID);
            String targetIdVer = LWM2M_SEPARATOR_PATH + FR_OBJECT_ID + LWM2M_SEPARATOR_KEY + verSupportedObject + LWM2M_SEPARATOR_PATH + 0 + LWM2M_SEPARATOR_PATH + 0;
            lwM2mTransportRequest.sendAllRequest(lwM2MClient.getRegistration(), targetIdVer, WRITE_REPLACE, ContentFormat.OPAQUE.getName(),
                    firmwareChunk, config.getTimeout(), null);
            log.warn("updateFirmwareClient [{}] [{}]", lwM2MClient.getFrUpdate().getCurrentFwVersion(), lwM2MClient.getFrUpdate().getClientFwVersion());
        }
    }


    /**
     * !!!  sharedAttr === profileAttr  !!!
     * Get names or keyNames from profile:  resources IsWritable
     *
     * @param lwM2MClient -
     * @return ArrayList  keyNames from profile profileAttr && IsWritable
     */
    private ConcurrentMap<String, String> getNamesFromProfileForSharedAttributes(LwM2mClient lwM2MClient) {

        LwM2mClientProfile profile = lwM2mClientContext.getProfile(lwM2MClient.getProfileId());
        return new Gson().fromJson(profile.getPostKeyNameProfile().toString(),
                new TypeToken<ConcurrentHashMap<String, String>>() {
                }.getType());
    }

    private boolean validateResourceInModel(LwM2mClient lwM2mClient, String pathIdVer, boolean isWritableNotOptional) {
        ResourceModel resourceModel = lwM2mClient.getResourceModel(pathIdVer, this.config
                .getModelProvider());
        Integer objectId = new LwM2mPath(convertPathFromIdVerToObjectId(pathIdVer)).getObjectId();
        String objectVer = validateObjectVerFromKey(pathIdVer);
        return resourceModel != null && (isWritableNotOptional ?
                objectId != null && objectVer != null && objectVer.equals(lwM2mClient.getRegistration().getSupportedVersion(objectId)) && resourceModel.operations.isWritable() :
                objectId != null && objectVer != null && objectVer.equals(lwM2mClient.getRegistration().getSupportedVersion(objectId)));
    }

}
