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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.leshan.core.attributes.Attribute;
import org.eclipse.leshan.core.attributes.AttributeSet;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mMultipleResource;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.codec.CodecException;
import org.eclipse.leshan.core.request.DownlinkRequest;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.server.registration.Registration;
import org.nustaq.serialization.FSTConfiguration;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.profile.Lwm2mDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.eclipse.leshan.core.attributes.Attribute.DIMENSION;
import static org.eclipse.leshan.core.attributes.Attribute.MAXIMUM_PERIOD;
import static org.eclipse.leshan.core.attributes.Attribute.MINIMUM_PERIOD;
import static org.eclipse.leshan.core.attributes.Attribute.OBJECT_VERSION;
import static org.thingsboard.server.common.data.lwm2m.LwM2mConstants.LWM2M_SEPARATOR_KEY;
import static org.thingsboard.server.common.data.lwm2m.LwM2mConstants.LWM2M_SEPARATOR_PATH;

@Slf4j
public class LwM2mTransportHandlerUtil {

    public static final String TRANSPORT_DEFAULT_LWM2M_VERSION = "1.0";
    public static final String CLIENT_LWM2M_SETTINGS = "clientLwM2mSettings";
    public static final String BOOTSTRAP = "bootstrap";
    public static final String SERVERS = "servers";
    public static final String LWM2M_SERVER = "lwm2mServer";
    public static final String BOOTSTRAP_SERVER = "bootstrapServer";
    public static final String OBSERVE_ATTRIBUTE_TELEMETRY = "observeAttr";
    public static final String ATTRIBUTE = "attribute";
    public static final String TELEMETRY = "telemetry";
    public static final String KEY_NAME = "keyName";
    public static final String OBSERVE_LWM2M = "observe";
    public static final String ATTRIBUTE_LWM2M = "attributeLwm2m";

    private static final String REQUEST = "/request";
    private static final String ATTRIBUTES = "/" + ATTRIBUTE;
    public static final String TELEMETRIES = "/" + TELEMETRY;
    public static final String ATTRIBUTES_REQUEST = ATTRIBUTES + REQUEST;
    public static final String DEVICE_ATTRIBUTES_REQUEST = ATTRIBUTES_REQUEST + "/";

    public static final long DEFAULT_TIMEOUT = 2 * 60 * 1000L; // 2min in ms

    public static final String LOG_LW2M_TELEMETRY = "logLwm2m";
    public static final String LOG_LW2M_INFO = "info";
    public static final String LOG_LW2M_ERROR = "error";
    public static final String LOG_LW2M_WARN = "warn";
    public static final String LOG_LW2M_VALUE = "value";

    public static final int LWM2M_STRATEGY_1 = 1;
    public static final int LWM2M_STRATEGY_2 = 2;

    public static final String CLIENT_NOT_AUTHORIZED = "Client not authorized";

    public static final Integer FR_OBJECT_ID = 5;
    public static final Integer FR_RESOURCE_VER_ID = 7;
    public static final String FR_PATH_RESOURCE_VER_ID = LWM2M_SEPARATOR_PATH + FR_OBJECT_ID + LWM2M_SEPARATOR_PATH
            + "0" + LWM2M_SEPARATOR_PATH + FR_RESOURCE_VER_ID;

    public enum LwM2mTypeServer {
        BOOTSTRAP(0, "bootstrap"),
        CLIENT(1, "client");

        public int code;
        public String type;

        LwM2mTypeServer(int code, String type) {
            this.code = code;
            this.type = type;
        }

        public static LwM2mTypeServer fromLwM2mTypeServer(String type) {
            for (LwM2mTypeServer sm : LwM2mTypeServer.values()) {
                if (sm.type.equals(type)) {
                    return sm;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported typeServer type : %d", type));
        }
    }

    /**
     * Define the behavior of a write request.
     */
    public enum LwM2mTypeOper {
        /**
         * GET
         */
        READ(0, "Read"),
        DISCOVER(1, "Discover"),
        OBSERVE_READ_ALL(2, "ObserveReadAll"),
        /**
         * POST
         */
        OBSERVE(3, "Observe"),
        OBSERVE_CANCEL(4, "ObserveCancel"),
        EXECUTE(5, "Execute"),
        /**
         * Replaces the Object Instance or the Resource(s) with the new value provided in the “Write” operation. (see
         * section 5.3.3 of the LW M2M spec).
         * if all resources are to be replaced
         */
        WRITE_REPLACE(6, "WriteReplace"),
        /**
         * PUT
         */
        /**
         * Adds or updates Resources provided in the new value and leaves other existing Resources unchanged. (see section
         * 5.3.3 of the LW M2M spec).
         * if this is a partial update request
         */
        WRITE_UPDATE(7, "WriteUpdate"),
        WRITE_ATTRIBUTES(8, "WriteAttributes"),
        DELETE(9, "Delete");

//        READ_INFO_FW(10, "ReadInfoFirmware");

        public int code;
        public String type;

        LwM2mTypeOper(int code, String type) {
            this.code = code;
            this.type = type;
        }

        public static LwM2mTypeOper fromLwLwM2mTypeOper(String type) {
            for (LwM2mTypeOper to : LwM2mTypeOper.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported typeOper type  : %d", type));
        }
    }

    public static final String EVENT_AWAKE = "AWAKE";
    public static final String SERVICE_CHANNEL = "SERVICE";
    public static final String RESPONSE_CHANNEL = "RESP";

    public static boolean equalsResourceValue(Object valueOld, Object valueNew, ResourceModel.Type type, LwM2mPath resourcePath) throws CodecException {
        switch (type) {
            case BOOLEAN:
            case INTEGER:
            case FLOAT:
                return String.valueOf(valueOld).equals(String.valueOf(valueNew));
            case TIME:
                return ((Date) valueOld).getTime() == ((Date) valueNew).getTime();
            case STRING:
            case OBJLNK:
                return valueOld.equals(valueNew);
            case OPAQUE:
                return Hex.decodeHex(((String) valueOld).toCharArray()).equals(Hex.decodeHex(((String) valueNew).toCharArray()));
            default:
                throw new CodecException("Invalid value type for resource %s, type %s", resourcePath, type);
        }
    }

    public static LwM2mNode getLvM2mNodeToObject(LwM2mNode content) {
        if (content instanceof LwM2mObject) {
            return (LwM2mObject) content;
        } else if (content instanceof LwM2mObjectInstance) {
            return (LwM2mObjectInstance) content;
        } else if (content instanceof LwM2mSingleResource) {
            return (LwM2mSingleResource) content;
        } else if (content instanceof LwM2mMultipleResource) {
            return (LwM2mMultipleResource) content;
        }
        return null;
    }


    public static LwM2mClientProfile getNewProfileParameters(JsonObject profilesConfigData, TenantId tenantId) {
        LwM2mClientProfile lwM2MClientProfile = new LwM2mClientProfile();
        lwM2MClientProfile.setTenantId(tenantId);
        lwM2MClientProfile.setPostClientLwM2mSettings(profilesConfigData.get(CLIENT_LWM2M_SETTINGS).getAsJsonObject());
        lwM2MClientProfile.setPostKeyNameProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).getAsJsonObject());
        lwM2MClientProfile.setPostAttributeProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).getAsJsonArray());
        lwM2MClientProfile.setPostTelemetryProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).getAsJsonArray());
        lwM2MClientProfile.setPostObserveProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE_LWM2M).getAsJsonArray());
        lwM2MClientProfile.setPostAttributeLwm2mProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE_LWM2M).getAsJsonObject());
        return lwM2MClientProfile;
    }

    /**
     * @return deviceProfileBody with Observe&Attribute&Telemetry From Thingsboard
     * Example:
     * property: {"clientLwM2mSettings": {
     * clientUpdateValueAfterConnect: false;
     * }
     * property: "observeAttr"
     * {"keyName": {
     * "/3/0/1": "modelNumber",
     * "/3/0/0": "manufacturer",
     * "/3/0/2": "serialNumber"
     * },
     * "attribute":["/2/0/1","/3/0/9"],
     * "telemetry":["/1/0/1","/2/0/1","/6/0/1"],
     * "observe":["/2/0","/2/0/0","/4/0/2"]}
     * "attributeLwm2m": {"/3_1.0": {"ver": "currentTimeTest11"},
     * "/3_1.0/0": {"gt": 17},
     * "/3_1.0/0/9": {"pmax": 45}, "/3_1.2": {ver": "3_1.2"}}
     */
    public static LwM2mClientProfile getLwM2MClientProfileFromThingsboard(DeviceProfile deviceProfile) {
        if (deviceProfile != null && ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties().size() > 0) {
            Object profile = ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties();
            try {
                ObjectMapper mapper = new ObjectMapper();
                String profileStr = mapper.writeValueAsString(profile);
                JsonObject profileJson = (profileStr != null) ? validateJson(profileStr) : null;
                return getValidateCredentialsBodyFromThingsboard(profileJson) ? LwM2mTransportHandlerUtil.getNewProfileParameters(profileJson, deviceProfile.getTenantId()) : null;
            } catch (IOException e) {
                log.error("", e);
            }
        }
        return null;
    }

    public static JsonObject getBootstrapParametersFromThingsboard(DeviceProfile deviceProfile) {
        if (deviceProfile != null && ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties().size() > 0) {
            Object bootstrap = ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties();
            try {
                ObjectMapper mapper = new ObjectMapper();
                String bootstrapStr = mapper.writeValueAsString(bootstrap);
                JsonObject objectMsg = (bootstrapStr != null) ? validateJson(bootstrapStr) : null;
                return (getValidateBootstrapProfileFromThingsboard(objectMsg)) ? objectMsg.get(BOOTSTRAP).getAsJsonObject() : null;
            } catch (IOException e) {
                log.error("", e);
            }
        }
        return null;
    }

    public static int getClientOnlyObserveAfterConnect(LwM2mClientProfile profile) {
        return profile.getPostClientLwM2mSettings().getAsJsonObject().has("clientOnlyObserveAfterConnect") ?
                profile.getPostClientLwM2mSettings().getAsJsonObject().get("clientOnlyObserveAfterConnect").getAsInt() : 1;
    }

    private static boolean getValidateCredentialsBodyFromThingsboard(JsonObject objectMsg) {
        return (objectMsg != null &&
                !objectMsg.isJsonNull() &&
                objectMsg.has(CLIENT_LWM2M_SETTINGS) &&
                !objectMsg.get(CLIENT_LWM2M_SETTINGS).isJsonNull() &&
                objectMsg.get(CLIENT_LWM2M_SETTINGS).isJsonObject() &&
                objectMsg.has(OBSERVE_ATTRIBUTE_TELEMETRY) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).isJsonObject() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(KEY_NAME) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).isJsonObject() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(ATTRIBUTE) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).isJsonArray() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(TELEMETRY) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).isJsonArray() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(OBSERVE_LWM2M) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE_LWM2M).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE_LWM2M).isJsonArray() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(ATTRIBUTE_LWM2M) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE_LWM2M).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE_LWM2M).isJsonObject());
    }

    private static boolean getValidateBootstrapProfileFromThingsboard(JsonObject objectMsg) {
        return (objectMsg != null &&
                !objectMsg.isJsonNull() &&
                objectMsg.has(BOOTSTRAP) &&
                objectMsg.get(BOOTSTRAP).isJsonObject() &&
                !objectMsg.get(BOOTSTRAP).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(SERVERS) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(SERVERS).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(SERVERS).isJsonObject() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(BOOTSTRAP_SERVER) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(BOOTSTRAP_SERVER).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(BOOTSTRAP_SERVER).isJsonObject() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(LWM2M_SERVER) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(LWM2M_SERVER).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(LWM2M_SERVER).isJsonObject());
    }


    public static JsonObject validateJson(String jsonStr) {
        JsonObject object = null;
        if (jsonStr != null && !jsonStr.isEmpty()) {
            String jsonValidFlesh = jsonStr.replaceAll("\\\\", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll("\n", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll("\t", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll(" ", "");
            String jsonValid = (jsonValidFlesh.charAt(0) == '"' && jsonValidFlesh.charAt(jsonValidFlesh.length() - 1) == '"') ? jsonValidFlesh.substring(1, jsonValidFlesh.length() - 1) : jsonValidFlesh;
            try {
                object = new JsonParser().parse(jsonValid).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                log.error("[{}] Fail validateJson [{}]", jsonStr, e.getMessage());
            }
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> decode(byte[] byteArray) {
        try {
            FSTConfiguration config = FSTConfiguration.createDefaultConfiguration();
            T msg = (T) config.asObject(byteArray);
            return Optional.ofNullable(msg);
        } catch (IllegalArgumentException e) {
            log.error("Error during deserialization message, [{}]", e.getMessage());
            return Optional.empty();
        }
    }

    public static String splitCamelCaseString(String s) {
        LinkedList<String> linkedListOut = new LinkedList<>();
        LinkedList<String> linkedList = new LinkedList<String>((Arrays.asList(s.split(" "))));
        linkedList.forEach(str -> {
            String strOut = str.replaceAll("\\W", "").replaceAll("_", "").toUpperCase();
            if (strOut.length() > 1) linkedListOut.add(strOut.charAt(0) + strOut.substring(1).toLowerCase());
            else linkedListOut.add(strOut);
        });
        linkedListOut.set(0, (linkedListOut.get(0).substring(0, 1).toLowerCase() + linkedListOut.get(0).substring(1)));
        return StringUtils.join(linkedListOut, "");
    }

    public static <T> TransportServiceCallback<Void> getAckCallback(LwM2mClient lwM2MClient, int requestId, String typeTopic) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("[{}] [{}] - requestId [{}] - EndPoint  , Access AckCallback", typeTopic, requestId, lwM2MClient.getEndpoint());
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg", e.toString());
            }
        };
    }

    public static String convertPathFromIdVerToObjectId(String pathIdVer) {
        try {
            String[] keyArray = pathIdVer.split(LWM2M_SEPARATOR_PATH);
            if (keyArray.length > 1 && keyArray[1].split(LWM2M_SEPARATOR_KEY).length == 2) {
                keyArray[1] = keyArray[1].split(LWM2M_SEPARATOR_KEY)[0];
                return StringUtils.join(keyArray, LWM2M_SEPARATOR_PATH);
            } else {
                return pathIdVer;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param path - pathId or pathIdVer
     * @return
     */
    public static String getVerFromPathIdVerOrId(String path) {
        try {
            String[] keyArray = path.split(LWM2M_SEPARATOR_PATH);
            if (keyArray.length > 1) {
                String[] keyArrayVer = keyArray[1].split(LWM2M_SEPARATOR_KEY);
                return keyArrayVer.length == 2 ? keyArrayVer[1] : null;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public static String validPathIdVer(String pathIdVer, Registration registration) throws IllegalArgumentException {
        if (pathIdVer.indexOf(LWM2M_SEPARATOR_PATH) < 0) {
            throw new IllegalArgumentException(String.format("Error:"));
        } else {
            String[] keyArray = pathIdVer.split(LWM2M_SEPARATOR_PATH);
            if (keyArray.length > 1 && keyArray[1].split(LWM2M_SEPARATOR_KEY).length == 2) {
                return pathIdVer;
            } else {
                LwM2mPath pathObjId = new LwM2mPath(pathIdVer);
                return convertPathFromObjectIdToIdVer(pathIdVer, registration);
            }
        }
    }

    public static String convertPathFromObjectIdToIdVer(String path, Registration registration) {
        String ver = registration.getSupportedObject().get(new LwM2mPath(path).getObjectId());
        try {
            String[] keyArray = path.split(LWM2M_SEPARATOR_PATH);
            if (keyArray.length > 1) {
                keyArray[1] = keyArray[1] + LWM2M_SEPARATOR_KEY + ver;
                return StringUtils.join(keyArray, LWM2M_SEPARATOR_PATH);
            } else {
                return path;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String validateObjectVerFromKey(String key) {
        try {
            return (key.split(LWM2M_SEPARATOR_PATH)[1].split(LWM2M_SEPARATOR_KEY)[1]);
        } catch (Exception e) {
            return ObjectModel.DEFAULT_VERSION;
        }
    }

    /**
     * As example:
     * a)Write-Attributes/3/0/9?pmin=1 means the Battery Level value will be notified
     * to the Server with a minimum interval of 1sec;
     * this value is set at theResource level.
     * b)Write-Attributes/3/0/9?pmin means the Battery Level will be notified
     * to the Server with a minimum value (pmin) given by the default one
     * (resource 2 of Object Server ID=1),
     * or with another value if this Attribute has been set at another level
     * (Object or Object Instance: see section5.1.1).
     * c)Write-Attributes/3/0?pmin=10 means that all Resources of Instance 0 of the Object ‘Device (ID:3)’
     * will be notified to the Server with a minimum interval of 10 sec;
     * this value is set at the Object Instance level.
     * d)Write-Attributes /3/0/9?gt=45&st=10 means the Battery Level will be notified to the Server
     * when:
     * a.old value is 20 and new value is 35 due to step condition
     * b.old value is 45 and new value is 50 due to gt condition
     * c.old value is 50 and new value is 40 due to both gt and step conditions
     * d.old value is 35 and new value is 20 due to step conditione)
     * Write-Attributes /3/0/9?lt=20&gt=85&st=10 means the Battery Level will be notified to the Server
     * when:
     * a.old value is 17 and new value is 24 due to lt condition
     * b.old value is 75 and new value is 90 due to both gt and step conditions
     * String uriQueries = "pmin=10&pmax=60";
     * AttributeSet attributes = AttributeSet.parse(uriQueries);
     * WriteAttributesRequest request = new WriteAttributesRequest(target, attributes);
     * Attribute gt = new Attribute(GREATER_THAN, Double.valueOf("45"));
     * Attribute st = new Attribute(LESSER_THAN, Double.valueOf("10"));
     * Attribute pmax = new Attribute(MAXIMUM_PERIOD, "60");
     * Attribute [] attrs = {gt, st};
     */
    public static DownlinkRequest createWriteAttributeRequest(String target, Object params) {
        AttributeSet attrSet = new AttributeSet(createWriteAttributes(params));
        return attrSet.getAttributes().size() > 0 ? new WriteAttributesRequest(target, attrSet) : null;
    }

    private static Attribute[] createWriteAttributes(Object params) {
        List attributeLists = new ArrayList<Attribute>();
        ObjectMapper oMapper = new ObjectMapper();
        Map<String, Object> map = oMapper.convertValue(params, ConcurrentHashMap.class);
        map.forEach((k, v) -> {
            if (!v.toString().isEmpty() || (v.toString().isEmpty() && OBJECT_VERSION.equals(k))) {
                attributeLists.add(new Attribute(k,
                        (DIMENSION.equals(k) || MINIMUM_PERIOD.equals(k) || MAXIMUM_PERIOD.equals(k)) ?
                                ((Double) v).longValue() : v));
            }
        });
        return (Attribute[]) attributeLists.toArray(Attribute[]::new);
    }


    public static Set<String> convertJsonArrayToSet(JsonArray jsonArray) {
        List<String> attributeListOld = new Gson().fromJson(jsonArray, new TypeToken<List<String>>() {
        }.getType());
        return Sets.newConcurrentHashSet(attributeListOld);
    }

}
