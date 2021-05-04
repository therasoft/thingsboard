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

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.ObjectLink;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.request.DownlinkRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.request.exception.ClientSleepingException;
import org.eclipse.leshan.core.response.CancelObservationResponse;
import org.eclipse.leshan.core.response.DeleteResponse;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.ResponseCallback;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.core.util.NamedThreadFactory;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.config.LwM2MTransportServerConfig;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientContext;
import org.thingsboard.server.transport.lwm2m.server.client.Lwm2mClientRpcRequest;
import org.thingsboard.server.transport.lwm2m.utils.LwM2mValueConverterImpl;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT;
import static org.eclipse.leshan.core.ResponseCode.BAD_REQUEST;
import static org.eclipse.leshan.core.ResponseCode.NOT_FOUND;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.DEFAULT_TIMEOUT;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.FR_PATH_RESOURCE_VER_ID;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_ERROR;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_INFO;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LOG_LW2M_VALUE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.OBSERVE_CANCEL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.LwM2mTypeOper.OBSERVE_READ_ALL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.RESPONSE_CHANNEL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.convertPathFromIdVerToObjectId;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.convertPathFromObjectIdToIdVer;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandlerUtil.createWriteAttributeRequest;

@Slf4j
@Service
@TbLwM2mTransportComponent
@RequiredArgsConstructor
public class LwM2mTransportRequest {
    private ExecutorService executorResponse;

    public LwM2mValueConverterImpl converter;

    private final LwM2mTransportContext context;
    private final LwM2MTransportServerConfig config;
    private final LwM2mTransportServerHelper lwM2MTransportServerHelper;
    private final LwM2mClientContext lwM2mClientContext;
    private final DefaultLwM2MTransportMsgHandler serviceImpl;

    @PostConstruct
    public void init() {
        this.converter = LwM2mValueConverterImpl.getInstance();
        executorResponse = Executors.newFixedThreadPool(this.config.getResponsePoolSize(),
                new NamedThreadFactory(String.format("LwM2M %s channel response", RESPONSE_CHANNEL)));
    }

    /**
     * Device management and service enablement, including Read, Write, Execute, Discover, Create, Delete and Write-Attributes
     *
     * @param registration      -
     * @param targetIdVer       -
     * @param typeOper          -
     * @param contentFormatName -
     */
    @SneakyThrows
    public void sendAllRequest(Registration registration, String targetIdVer, LwM2mTypeOper typeOper,
                               String contentFormatName, Object params, long timeoutInMs, Lwm2mClientRpcRequest rpcRequest) {
        try {
            String target = convertPathFromIdVerToObjectId(targetIdVer);
            DownlinkRequest request = null;
            ContentFormat contentFormat = contentFormatName != null ? ContentFormat.fromName(contentFormatName.toUpperCase()) : ContentFormat.DEFAULT;
            LwM2mClient lwM2MClient = this.lwM2mClientContext.getLwM2mClientWithReg(registration, null);
            LwM2mPath resultIds = target != null ? new LwM2mPath(target) : null;
            if (!OBSERVE_READ_ALL.name().equals(typeOper.name()) && resultIds != null && registration != null && resultIds.getObjectId() >= 0 &&
                    lwM2MClient != null) {
                if (lwM2MClient.isValidObjectVersion(targetIdVer)) {
                    timeoutInMs = timeoutInMs > 0 ? timeoutInMs : DEFAULT_TIMEOUT;
                    ResourceModel resourceModel = null;
                    switch (typeOper) {
                        case READ:
                            request = new ReadRequest(contentFormat, target);
                            break;
                        case DISCOVER:
                            request = new DiscoverRequest(target);
                            break;
                        case OBSERVE:
                            if (resultIds.isResource()) {
                                request = new ObserveRequest(contentFormat, resultIds.getObjectId(), resultIds.getObjectInstanceId(), resultIds.getResourceId());
                            } else if (resultIds.isObjectInstance()) {
                                request = new ObserveRequest(contentFormat, resultIds.getObjectId(), resultIds.getObjectInstanceId());
                            } else if (resultIds.getObjectId() >= 0) {
                                request = new ObserveRequest(contentFormat, resultIds.getObjectId());
                            }
                            break;
                        case OBSERVE_CANCEL:
                            /**
                             * lwM2MTransportRequest.sendAllRequest(lwServer, registration, path, POST_TYPE_OPER_OBSERVE_CANCEL, null, null, null, null, context.getTimeout());
                             * At server side this will not remove the observation from the observation store, to do it you need to use
                             * {@code ObservationService#cancelObservation()}
                             */
                            context.getServer().getObservationService().cancelObservations(registration, target);
                            break;
                        case EXECUTE:
                            resourceModel = lwM2MClient.getResourceModel(targetIdVer, this.config
                                    .getModelProvider());
                            if (params != null && !resourceModel.multiple) {
                                request = new ExecuteRequest(target, (String) this.converter.convertValue(params, resourceModel.type, ResourceModel.Type.STRING, resultIds));
                            } else {
                                request = new ExecuteRequest(target);
                            }
                            break;
                        case WRITE_REPLACE:
                            // Request to write a <b>String Single-Instance Resource</b> using the TLV content format.
                            resourceModel = lwM2MClient.getResourceModel(targetIdVer, this.config
                                    .getModelProvider());
                            if (contentFormat.equals(ContentFormat.TLV)) {
                                request = this.getWriteRequestSingleResource(null, resultIds.getObjectId(),
                                        resultIds.getObjectInstanceId(), resultIds.getResourceId(), params, resourceModel.type,
                                        registration, rpcRequest);
                            }
                            // Mode.REPLACE && Request to write a <b>String Single-Instance Resource</b> using the given content format (TEXT, TLV, JSON)
                            else if (!contentFormat.equals(ContentFormat.TLV)) {
                                request = this.getWriteRequestSingleResource(contentFormat, resultIds.getObjectId(),
                                        resultIds.getObjectInstanceId(), resultIds.getResourceId(), params, resourceModel.type,
                                        registration, rpcRequest);
                            }
                            break;
                        case WRITE_UPDATE:
//                            LwM2mNode node = null;
//                            if (resultIds.isObjectInstance()) {
//                                node = new LwM2mObjectInstance(resultIds.getObjectInstanceId(), lwM2MClient.
//                                        getNewResourcesForInstance(targetIdVer, this.lwM2mTransportContextServer.getLwM2MTransportConfigServer().getModelProvider(),
//                                                this.converter));
//                                request = new WriteRequest(WriteRequest.Mode.UPDATE, contentFormat, target, node);
//                            } else if (resultIds.getObjectId() >= 0) {
//                                request = new ObserveRequest(resultIds.getObjectId());
//                            }
                            break;
                        case WRITE_ATTRIBUTES:
                            request = createWriteAttributeRequest(target, params);
                            break;
                        case DELETE:
                            request = new DeleteRequest(target);
                            break;
                    }

                    if (request != null) {
                        try {
                            this.sendRequest(registration, lwM2MClient, request, timeoutInMs, rpcRequest);
                        } catch (ClientSleepingException e) {
                            DownlinkRequest finalRequest = request;
                            long finalTimeoutInMs = timeoutInMs;
                            lwM2MClient.getQueuedRequests().add(() -> sendRequest(registration, lwM2MClient, finalRequest, finalTimeoutInMs, rpcRequest));
                        } catch (Exception e) {
                            log.error("[{}] [{}] [{}] Failed to send downlink.", registration.getEndpoint(), targetIdVer, typeOper.name(), e);
                        }
                    } else if (OBSERVE_CANCEL == typeOper) {
                        log.trace("[{}], [{}] - [{}] SendRequest", registration.getEndpoint(), typeOper.name(), targetIdVer);
                        if (rpcRequest != null) {
                            rpcRequest.setInfoMsg(null);
                            serviceImpl.sentRpcRequest(rpcRequest, CONTENT.name(), null, null);
                        }
                    } else {
                        log.error("[{}], [{}] - [{}] error SendRequest", registration.getEndpoint(), typeOper.name(), targetIdVer);
                        if (rpcRequest != null) {
                            String errorMsg = resourceModel == null ? String.format("Path %s not found in object version", targetIdVer) : "SendRequest - null";
                            serviceImpl.sentRpcRequest(rpcRequest, NOT_FOUND.getName(), errorMsg, LOG_LW2M_ERROR);
                        }
                    }
                } else if (rpcRequest != null) {
                    String errorMsg = String.format("Path %s not found in object version", targetIdVer);
                    serviceImpl.sentRpcRequest(rpcRequest, NOT_FOUND.getName(), errorMsg, LOG_LW2M_ERROR);
                }
            } else if (OBSERVE_READ_ALL.name().equals(typeOper.name())) {
                Set<Observation> observations = context.getServer().getObservationService().getObservations(registration);
                Set<String> observationPaths = observations.stream().map(observation -> observation.getPath().toString()).collect(Collectors.toUnmodifiableSet());
                String msg = String.format("%s: type operation %s observation paths - %s", LOG_LW2M_INFO,
                        OBSERVE_READ_ALL.type, observationPaths);
                serviceImpl.sendLogsToThingsboard(msg, registration.getId());
                log.trace("[{}] [{}],  [{}]", typeOper.name(), registration.getEndpoint(), msg);
                if (rpcRequest != null) {
                    String valueMsg = String.format("Observation paths - %s", observationPaths);
                    serviceImpl.sentRpcRequest(rpcRequest, CONTENT.name(), valueMsg, LOG_LW2M_VALUE);
                }
            }
        } catch (Exception e) {
            String msg = String.format("%s: type operation %s  %s", LOG_LW2M_ERROR,
                    typeOper.name(), e.getMessage());
            serviceImpl.sendLogsToThingsboard(msg, registration.getId());
            throw new Exception(e);
        }
    }

    /**
     * @param registration -
     * @param request      -
     * @param timeoutInMs  -
     */

    @SuppressWarnings("unchecked")
    private void sendRequest(Registration registration, LwM2mClient lwM2MClient, DownlinkRequest request, long timeoutInMs, Lwm2mClientRpcRequest rpcRequest) {
        context.getServer().send(registration, request, timeoutInMs, (ResponseCallback<?>) response -> {
            if (!lwM2MClient.isInit()) {
                lwM2MClient.initReadValue(this.serviceImpl, convertPathFromObjectIdToIdVer(request.getPath().toString(), registration));
            }
            if (CoAP.ResponseCode.isSuccess(((Response) response.getCoapResponse()).getCode())) {
                this.handleResponse(registration, request.getPath().toString(), response, request, rpcRequest);
            } else {
                String msg = String.format("%s: SendRequest %s: CoapCode - %s Lwm2m code - %d name - %s Resource path - %s", LOG_LW2M_ERROR, request.getClass().getName().toString(),
                        ((Response) response.getCoapResponse()).getCode(), response.getCode().getCode(), response.getCode().getName(), request.getPath().toString());
                serviceImpl.sendLogsToThingsboard(msg, registration.getId());
                log.error("[{}] [{}], [{}] - [{}] [{}] error SendRequest", request.getClass().getName().toString(), registration.getEndpoint(),
                        ((Response) response.getCoapResponse()).getCode(), response.getCode(), request.getPath().toString());
                if (!lwM2MClient.isInit()) {
                    lwM2MClient.initReadValue(this.serviceImpl, convertPathFromObjectIdToIdVer(request.getPath().toString(), registration));
                }
                if (rpcRequest != null) {
                    serviceImpl.sentRpcRequest(rpcRequest, response.getCode().getName(), response.getErrorMessage(), LOG_LW2M_ERROR);
                }
                /** Not Found
                 * set setClient_fw_version = empty
                 **/
                if (FR_PATH_RESOURCE_VER_ID.equals(request.getPath().toString()) && lwM2MClient.isUpdateFw()) {
                    lwM2MClient.setUpdateFw(false);
                    lwM2MClient.getFrUpdate().setClientFwVersion("");
                    log.warn("updateFirmwareClient1");
                    serviceImpl.updateFirmwareClient(lwM2MClient);
                }
            }
        }, e -> {
            /** version == null
             * set setClient_fw_version = empty
             **/
            if (FR_PATH_RESOURCE_VER_ID.equals(request.getPath().toString()) && lwM2MClient.isUpdateFw()) {
                lwM2MClient.setUpdateFw(false);
                lwM2MClient.getFrUpdate().setClientFwVersion("");
                log.warn("updateFirmwareClient2");
                serviceImpl.updateFirmwareClient(lwM2MClient);
            }
            if (!lwM2MClient.isInit()) {
                lwM2MClient.initReadValue(this.serviceImpl, convertPathFromObjectIdToIdVer(request.getPath().toString(), registration));
            }
            String msg = String.format("%s: SendRequest %s: Resource path - %s msg error - %s",
                    LOG_LW2M_ERROR, request.getClass().getName().toString(), request.getPath().toString(), e.getMessage());
            serviceImpl.sendLogsToThingsboard(msg, registration.getId());
            log.error("[{}] [{}] - [{}] error SendRequest", request.getClass().getName().toString(), request.getPath().toString(), e.toString());
            if (rpcRequest != null) {
                serviceImpl.sentRpcRequest(rpcRequest, CoAP.CodeClass.ERROR_RESPONSE.name(), e.getMessage(), LOG_LW2M_ERROR);
            }
        });
    }

    private WriteRequest getWriteRequestSingleResource(ContentFormat contentFormat, Integer objectId, Integer instanceId,
                                                       Integer resourceId, Object value, ResourceModel.Type type,
                                                       Registration registration, Lwm2mClientRpcRequest rpcRequest) {
        try {
            if (type != null) {
                switch (type) {
                    case STRING:    // String
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, value.toString()) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, value.toString());
                    case INTEGER:   // Long
                        final long valueInt = Integer.toUnsignedLong(Integer.parseInt(value.toString()));
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, valueInt) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, valueInt);
                    case OBJLNK:    // ObjectLink
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, ObjectLink.fromPath(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, ObjectLink.fromPath(value.toString()));
                    case BOOLEAN:   // Boolean
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, Boolean.parseBoolean(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, Boolean.parseBoolean(value.toString()));
                    case FLOAT:     // Double
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, Double.parseDouble(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, Double.parseDouble(value.toString()));
                    case TIME:      // Date
                        Date date = new Date(Long.decode(value.toString()));
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, date) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, date);
                    case OPAQUE:    // byte[] value, base64
                        byte[] valueRequest = value instanceof byte[] ? (byte[]) value : Hex.decodeHex(value.toString().toCharArray());
                        return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, valueRequest) :
                                new WriteRequest(contentFormat, objectId, instanceId, resourceId, valueRequest);
                    default:
                }
            }
            if (rpcRequest != null) {
                String patn = "/" + objectId + "/" + instanceId + "/" + resourceId;
                String errorMsg = String.format("Bad ResourceModel Operations (E): Resource path - %s ResourceModel type - %s", patn, type);
                rpcRequest.setErrorMsg(errorMsg);
            }
            return null;
        } catch (NumberFormatException e) {
            String patn = "/" + objectId + "/" + instanceId + "/" + resourceId;
            String msg = String.format(LOG_LW2M_ERROR + ": NumberFormatException: Resource path - %s type - %s value - %s msg error - %s  SendRequest to Client",
                    patn, type, value, e.toString());
            serviceImpl.sendLogsToThingsboard(msg, registration.getId());
            log.error("Path: [{}] type: [{}] value: [{}] errorMsg: [{}]]", patn, type, value, e.toString());
            if (rpcRequest != null) {
                String errorMsg = String.format("NumberFormatException: Resource path - %s type - %s value - %s", patn, type, value);
                serviceImpl.sentRpcRequest(rpcRequest, BAD_REQUEST.getName(), errorMsg, LOG_LW2M_ERROR);
            }
            return null;
        }
    }

    private void handleResponse(Registration registration, final String path, LwM2mResponse response,
                                DownlinkRequest request, Lwm2mClientRpcRequest rpcRequest) {
        executorResponse.submit(() -> {
            try {
                this.sendResponse(registration, path, response, request, rpcRequest);
            } catch (Exception e) {
                log.error("[{}] endpoint [{}] path [{}] Exception Unable to after send response.", registration.getEndpoint(), path, e);
            }
        });
    }

    /**
     * processing a response from a client
     *
     * @param registration -
     * @param path         -
     * @param response     -
     */
    private void sendResponse(Registration registration, String path, LwM2mResponse response,
                              DownlinkRequest request, Lwm2mClientRpcRequest rpcRequest) {
        String pathIdVer = convertPathFromObjectIdToIdVer(path, registration);
        if (response instanceof ReadResponse) {
            serviceImpl.onUpdateValueAfterReadResponse(registration, pathIdVer, (ReadResponse) response, rpcRequest);
        } else if (response instanceof CancelObservationResponse) {
            log.info("[{}] Path [{}] CancelObservationResponse 3_Send", pathIdVer, response);

        } else if (response instanceof DeleteResponse) {
            log.info("[{}] Path [{}] DeleteResponse 5_Send", pathIdVer, response);
        } else if (response instanceof DiscoverResponse) {
            log.info("[{}] [{}] - [{}] [{}] Discovery value: [{}]", registration.getEndpoint(),
                    ((Response) response.getCoapResponse()).getCode(), response.getCode(),
                    request.getPath().toString(), ((DiscoverResponse) response).getObjectLinks());
            if (rpcRequest != null) {
                String discoveryMsg = String.format("%s",
                        Arrays.stream(((DiscoverResponse) response).getObjectLinks()).collect(Collectors.toSet()));
                serviceImpl.sentRpcRequest(rpcRequest, response.getCode().getName(), discoveryMsg, LOG_LW2M_VALUE);
            }
        } else if (response instanceof ExecuteResponse) {
            log.info("[{}] Path [{}] ExecuteResponse  7_Send", pathIdVer, response);
        } else if (response instanceof WriteAttributesResponse) {
            log.info("[{}] Path [{}] WriteAttributesResponse 8_Send", pathIdVer, response);
        } else if (response instanceof WriteResponse) {
            log.info("[{}] Path [{}] WriteResponse 9_Send", pathIdVer, response);
            this.infoWriteResponse(registration, response, request);
            serviceImpl.onWriteResponseOk(registration, pathIdVer, (WriteRequest) request);
        }
        if (rpcRequest != null) {
            if (response instanceof ExecuteResponse
                    || response instanceof WriteAttributesResponse
                    || response instanceof DeleteResponse) {
                rpcRequest.setInfoMsg(null);
                serviceImpl.sentRpcRequest(rpcRequest, response.getCode().getName(), null, null);
            } else if (response instanceof WriteResponse) {
                serviceImpl.sentRpcRequest(rpcRequest, response.getCode().getName(), null, LOG_LW2M_INFO);
            }
        }
    }

    private void infoWriteResponse(Registration registration, LwM2mResponse response, DownlinkRequest request) {
        try {
            LwM2mNode node = ((WriteRequest) request).getNode();
            String msg;
            Object value;
            LwM2mSingleResource singleResource = (LwM2mSingleResource) node;
            if (singleResource.getType() == ResourceModel.Type.STRING || singleResource.getType() == ResourceModel.Type.OPAQUE) {
                int valueLength;
                if (singleResource.getType() == ResourceModel.Type.STRING) {
                    valueLength = ((String) singleResource.getValue()).length();
                    value = ((String) singleResource.getValue())
                            .substring(Math.min(valueLength, config.getLogMaxLength()));

                } else {
                    valueLength = ((byte[]) singleResource.getValue()).length;
                    value = new String(Arrays.copyOf(((byte[]) singleResource.getValue()),
                            Math.min(valueLength, config.getLogMaxLength())));
                }
                value = valueLength > config.getLogMaxLength() ? value + "..." : value;
                msg = String.format("%s: Update finished successfully: Lwm2m code - %d Resource path - %s length - %s value - %s",
                        LOG_LW2M_INFO, response.getCode().getCode(), request.getPath().toString(), valueLength, value);
            } else {
                value = this.converter.convertValue(singleResource.getValue(),
                        singleResource.getType(), ResourceModel.Type.STRING, request.getPath());
                msg = String.format("%s: Update finished successfully: Lwm2m code - %d Resource path - %s value - %s",
                        LOG_LW2M_INFO, response.getCode().getCode(), request.getPath().toString(), value);
            }
            if (msg != null) {
                serviceImpl.sendLogsToThingsboard(msg, registration.getId());
                log.warn("[{}] [{}] [{}] - [{}] [{}] Update finished successfully: [{}]", request.getClass().getName(), registration.getEndpoint(),
                        ((Response) response.getCoapResponse()).getCode(), response.getCode(), request.getPath().toString(), value);
            }
        } catch (Exception e) {
            log.trace("Fail convert value from request to string. ", e);
        }
    }
}
