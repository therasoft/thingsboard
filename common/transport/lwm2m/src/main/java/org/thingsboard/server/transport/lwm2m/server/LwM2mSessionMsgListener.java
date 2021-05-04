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

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.ResourceType;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.AttributeUpdateNotificationMsg;
import org.thingsboard.server.gen.transport.TransportProtos.GetAttributeResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.SessionCloseNotificationProto;
import org.thingsboard.server.gen.transport.TransportProtos.ToDeviceRpcRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToServerRpcResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToTransportUpdateCredentialsProto;

import java.util.Optional;

@Slf4j
public class LwM2mSessionMsgListener implements GenericFutureListener<Future<? super Void>>, SessionMsgListener {
    private DefaultLwM2MTransportMsgHandler service;
    private TransportProtos.SessionInfoProto sessionInfo;

    public LwM2mSessionMsgListener(DefaultLwM2MTransportMsgHandler service, TransportProtos.SessionInfoProto sessionInfo) {
        this.service = service;
        this.sessionInfo = sessionInfo;
    }

    @Override
    public void onGetAttributesResponse(GetAttributeResponseMsg getAttributesResponse) {
        this.service.onGetAttributesResponse(getAttributesResponse, this.sessionInfo);
    }

    @Override
    public void onAttributeUpdate(AttributeUpdateNotificationMsg attributeUpdateNotification) {
        this.service.onAttributeUpdate(attributeUpdateNotification, this.sessionInfo);
     }

    @Override
    public void onRemoteSessionCloseCommand(SessionCloseNotificationProto sessionCloseNotification) {
        log.info("[{}] sessionCloseNotification", sessionCloseNotification);
    }

    @Override
    public void onToTransportUpdateCredentials(ToTransportUpdateCredentialsProto updateCredentials) {
        this.service.onToTransportUpdateCredentials(updateCredentials);
    }

    @Override
    public void onDeviceProfileUpdate(TransportProtos.SessionInfoProto sessionInfo, DeviceProfile deviceProfile) {
        this.service.onDeviceProfileUpdate(sessionInfo, deviceProfile);
    }

    @Override
    public void onDeviceUpdate(TransportProtos.SessionInfoProto sessionInfo, Device device, Optional<DeviceProfile> deviceProfileOpt) {
        this.service.onDeviceUpdate(sessionInfo, device, deviceProfileOpt);
    }

    @Override
    public void onToDeviceRpcRequest(ToDeviceRpcRequestMsg toDeviceRequest) {
        this.service.onToDeviceRpcRequest(toDeviceRequest,this.sessionInfo);
    }

    @Override
    public void onToServerRpcResponse(ToServerRpcResponseMsg toServerResponse) {
        this.service.onToServerRpcResponse(toServerResponse);
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        log.info("[{}]  operationComplete", future);
    }

    @Override
    public void onResourceUpdate(@NotNull Optional<TransportProtos.ResourceUpdateMsg> resourceUpdateMsgOpt) {
        if (ResourceType.LWM2M_MODEL.name().equals(resourceUpdateMsgOpt.get().getResourceType())) {
            this.service.onResourceUpdate(resourceUpdateMsgOpt);
        }
    }

    @Override
    public void onResourceDelete(@NotNull Optional<TransportProtos.ResourceDeleteMsg> resourceDeleteMsgOpt) {
        if (ResourceType.LWM2M_MODEL.name().equals(resourceDeleteMsgOpt.get().getResourceType())) {
            this.service.onResourceDelete(resourceDeleteMsgOpt);
        }
    }
}
