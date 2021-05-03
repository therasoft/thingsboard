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
package org.thingsboard.server.controller;

import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.Firmware;
import org.thingsboard.server.common.data.FirmwareInfo;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.firmware.FirmwareType;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.FirmwareId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.nio.ByteBuffer;

@Slf4j
@RestController
@TbCoreComponent
@RequestMapping("/api")
public class FirmwareController extends BaseController {

    public static final String FIRMWARE_ID = "firmwareId";

    @PreAuthorize("hasAnyAuthority( 'TENANT_ADMIN')")
    @RequestMapping(value = "/firmware/{firmwareId}/download", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<org.springframework.core.io.Resource> downloadFirmware(@PathVariable(FIRMWARE_ID) String strFirmwareId) throws ThingsboardException {
        checkParameter(FIRMWARE_ID, strFirmwareId);
        try {
            FirmwareId firmwareId = new FirmwareId(toUUID(strFirmwareId));
            Firmware firmware = checkFirmwareId(firmwareId, Operation.READ);

            ByteArrayResource resource = new ByteArrayResource(firmware.getData().array());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + firmware.getFileName())
                    .header("x-filename", firmware.getFileName())
                    .contentLength(resource.contentLength())
                    .contentType(parseMediaType(firmware.getContentType()))
                    .body(resource);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/firmware/info/{firmwareId}", method = RequestMethod.GET)
    @ResponseBody
    public FirmwareInfo getFirmwareInfoById(@PathVariable(FIRMWARE_ID) String strFirmwareId) throws ThingsboardException {
        checkParameter(FIRMWARE_ID, strFirmwareId);
        try {
            FirmwareId firmwareId = new FirmwareId(toUUID(strFirmwareId));
            return checkNotNull(firmwareService.findFirmwareInfoById(getTenantId(), firmwareId));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/firmware/{firmwareId}", method = RequestMethod.GET)
    @ResponseBody
    public Firmware getFirmwareById(@PathVariable(FIRMWARE_ID) String strFirmwareId) throws ThingsboardException {
        checkParameter(FIRMWARE_ID, strFirmwareId);
        try {
            FirmwareId firmwareId = new FirmwareId(toUUID(strFirmwareId));
            return checkFirmwareId(firmwareId, Operation.READ);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/firmware", method = RequestMethod.POST)
    @ResponseBody
    public FirmwareInfo saveFirmwareInfo(@RequestBody FirmwareInfo firmwareInfo) throws ThingsboardException {
        boolean created = firmwareInfo.getId() == null;
        try {
            firmwareInfo.setTenantId(getTenantId());
            checkEntity(firmwareInfo.getId(), firmwareInfo, Resource.FIRMWARE);
            FirmwareInfo savedFirmwareInfo = firmwareService.saveFirmwareInfo(firmwareInfo);
            logEntityAction(savedFirmwareInfo.getId(), savedFirmwareInfo,
                    null, created ? ActionType.ADDED : ActionType.UPDATED, null);
            return savedFirmwareInfo;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.FIRMWARE), firmwareInfo,
                    null, created ? ActionType.ADDED : ActionType.UPDATED, e);
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/firmware/{firmwareId}", method = RequestMethod.POST)
    @ResponseBody
    public Firmware saveFirmwareData(@PathVariable(FIRMWARE_ID) String strFirmwareId,
                                     @RequestParam(required = false) String checksum,
                                     @RequestParam(required = false) String checksumAlgorithm,
                                     @RequestBody MultipartFile file) throws ThingsboardException {
        checkParameter(FIRMWARE_ID, strFirmwareId);
        try {
            FirmwareId firmwareId = new FirmwareId(toUUID(strFirmwareId));
            FirmwareInfo info = checkFirmwareInfoId(firmwareId, Operation.READ);

            Firmware firmware = new Firmware(firmwareId);
            firmware.setCreatedTime(info.getCreatedTime());
            firmware.setTenantId(getTenantId());
            firmware.setDeviceProfileId(info.getDeviceProfileId());
            firmware.setType(info.getType());
            firmware.setTitle(info.getTitle());
            firmware.setVersion(info.getVersion());
            firmware.setAdditionalInfo(info.getAdditionalInfo());

            byte[] data = file.getBytes();
            if (StringUtils.isEmpty(checksumAlgorithm)) {
                checksumAlgorithm = "sha256";
                checksum = Hashing.sha256().hashBytes(data).toString();
            }

            firmware.setChecksumAlgorithm(checksumAlgorithm);
            firmware.setChecksum(checksum);
            firmware.setFileName(file.getOriginalFilename());
            firmware.setContentType(file.getContentType());
            firmware.setData(ByteBuffer.wrap(data));
            firmware.setDataSize((long) data.length);
            Firmware savedFirmware = firmwareService.saveFirmware(firmware);
            logEntityAction(savedFirmware.getId(), savedFirmware, null, ActionType.UPDATED, null);
            return savedFirmware;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.FIRMWARE), null, null, ActionType.UPDATED, e, strFirmwareId);
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/firmwares", method = RequestMethod.GET)
    @ResponseBody
    public PageData<FirmwareInfo> getFirmwares(@RequestParam int pageSize,
                                               @RequestParam int page,
                                               @RequestParam(required = false) String textSearch,
                                               @RequestParam(required = false) String sortProperty,
                                               @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            return checkNotNull(firmwareService.findTenantFirmwaresByTenantId(getTenantId(), pageLink));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/firmwares/{deviceProfileId}/{type}/{hasData}", method = RequestMethod.GET)
    @ResponseBody
    public PageData<FirmwareInfo> getFirmwares(@PathVariable("deviceProfileId") String strDeviceProfileId,
                                               @PathVariable("type") String strType,
                                               @PathVariable("hasData") boolean hasData,
                                               @RequestParam int pageSize,
                                               @RequestParam int page,
                                               @RequestParam(required = false) String textSearch,
                                               @RequestParam(required = false) String sortProperty,
                                               @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter("deviceProfileId", strDeviceProfileId);
        checkParameter("type", strType);
        try {
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            return checkNotNull(firmwareService.findTenantFirmwaresByTenantIdAndDeviceProfileIdAndTypeAndHasData(getTenantId(),
                    new DeviceProfileId(toUUID(strDeviceProfileId)), FirmwareType.valueOf(strType), hasData, pageLink));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/firmware/{firmwareId}", method = RequestMethod.DELETE)
    @ResponseBody
    public void deleteFirmware(@PathVariable("firmwareId") String strFirmwareId) throws ThingsboardException {
        checkParameter(FIRMWARE_ID, strFirmwareId);
        try {
            FirmwareId firmwareId = new FirmwareId(toUUID(strFirmwareId));
            FirmwareInfo info = checkFirmwareInfoId(firmwareId, Operation.DELETE);
            firmwareService.deleteFirmware(getTenantId(), firmwareId);
            logEntityAction(firmwareId, info, null, ActionType.DELETED, null, strFirmwareId);
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.FIRMWARE), null, null, ActionType.DELETED, e, strFirmwareId);
            throw handleException(e);
        }
    }

}
