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
package org.thingsboard.server.dao.sql.firmware;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.FirmwareInfo;
import org.thingsboard.server.common.data.firmware.FirmwareType;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.FirmwareId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.firmware.FirmwareInfoDao;
import org.thingsboard.server.dao.model.sql.FirmwareInfoEntity;
import org.thingsboard.server.dao.sql.JpaAbstractSearchTextDao;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
public class JpaFirmwareInfoDao extends JpaAbstractSearchTextDao<FirmwareInfoEntity, FirmwareInfo> implements FirmwareInfoDao {

    @Autowired
    private FirmwareInfoRepository firmwareInfoRepository;

    @Override
    protected Class<FirmwareInfoEntity> getEntityClass() {
        return FirmwareInfoEntity.class;
    }

    @Override
    protected CrudRepository<FirmwareInfoEntity, UUID> getCrudRepository() {
        return firmwareInfoRepository;
    }

    @Override
    public FirmwareInfo findById(TenantId tenantId, UUID id) {
        return DaoUtil.getData(firmwareInfoRepository.findFirmwareInfoById(id));
    }

    @Override
    public FirmwareInfo save(TenantId tenantId, FirmwareInfo firmwareInfo) {
        FirmwareInfo savedFirmware = super.save(tenantId, firmwareInfo);
        if (firmwareInfo.getId() == null) {
            return savedFirmware;
        } else {
            return findById(tenantId, savedFirmware.getId().getId());
        }
    }

    @Override
    public PageData<FirmwareInfo> findFirmwareInfoByTenantId(TenantId tenantId, PageLink pageLink) {
        return DaoUtil.toPageData(firmwareInfoRepository
                .findAllByTenantId(
                        tenantId.getId(),
                        Objects.toString(pageLink.getTextSearch(), ""),
                        DaoUtil.toPageable(pageLink)));
    }

    @Override
    public PageData<FirmwareInfo> findFirmwareInfoByTenantIdAndDeviceProfileIdAndTypeAndHasData(TenantId tenantId, DeviceProfileId deviceProfileId, FirmwareType firmwareType, boolean hasData, PageLink pageLink) {
        return DaoUtil.toPageData(firmwareInfoRepository
                .findAllByTenantIdAndTypeAndDeviceProfileIdAndHasData(
                        tenantId.getId(),
                        deviceProfileId.getId(),
                        firmwareType,
                        hasData,
                        Objects.toString(pageLink.getTextSearch(), ""),
                        DaoUtil.toPageable(pageLink)));
    }

    @Override
    public boolean isFirmwareUsed(FirmwareId firmwareId, FirmwareType type, DeviceProfileId deviceProfileId) {
        return firmwareInfoRepository.isFirmwareUsed(firmwareId.getId(), deviceProfileId.getId(), type.name());
    }
}
