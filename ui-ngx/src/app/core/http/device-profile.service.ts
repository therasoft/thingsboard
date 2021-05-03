///
/// Copyright © 2016-2021 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {PageLink} from '@shared/models/page/page-link';
import {defaultHttpOptionsFromConfig, RequestConfig} from './http-utils';
import {Observable} from 'rxjs';
import {PageData} from '@shared/models/page/page-data';
import {DeviceProfile, DeviceProfileInfo, DeviceTransportType} from '@shared/models/device.models';
import {isDefinedAndNotNull, isEmptyStr} from '@core/utils';
import {ObjectLwM2M, ServerSecurityConfig} from '@home/components/profile/device/lwm2m/lwm2m-profile-config.models';
import {SortOrder} from '@shared/models/page/sort-order';

@Injectable({
  providedIn: 'root'
})
export class DeviceProfileService {

  constructor(
    private http: HttpClient
  ) {
  }

  public getDeviceProfiles(pageLink: PageLink, config?: RequestConfig): Observable<PageData<DeviceProfile>> {
    return this.http.get<PageData<DeviceProfile>>(`/api/deviceProfiles${pageLink.toQuery()}`, defaultHttpOptionsFromConfig(config));
  }

  public getDeviceProfile(deviceProfileId: string, config?: RequestConfig): Observable<DeviceProfile> {
    return this.http.get<DeviceProfile>(`/api/deviceProfile/${deviceProfileId}`, defaultHttpOptionsFromConfig(config));
  }

  public getLwm2mObjects(sortOrder: SortOrder, objectIds?: string[], searchText?: string, config?: RequestConfig):
    Observable<Array<ObjectLwM2M>> {
    let url = `/api/resource/lwm2m/?sortProperty=${sortOrder.property}&sortOrder=${sortOrder.direction}`;
    if (isDefinedAndNotNull(objectIds) && objectIds.length > 0) {
      url += `&objectIds=${objectIds}`;
    }
    if (isDefinedAndNotNull(searchText) && !isEmptyStr(searchText)) {
      url += `&searchText=${searchText}`;
    }
    return this.http.get<Array<ObjectLwM2M>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getLwm2mBootstrapSecurityInfo(securityMode: string, bootstrapServerIs: boolean,
                                       config?: RequestConfig): Observable<ServerSecurityConfig> {
    return this.http.get<ServerSecurityConfig>(
      `/api/lwm2m/deviceProfile/bootstrap/${securityMode}/${bootstrapServerIs}`,
      defaultHttpOptionsFromConfig(config)
    );
  }

  public getLwm2mObjectsPage(pageLink: PageLink, config?: RequestConfig): Observable<Array<ObjectLwM2M>> {
    return this.http.get<Array<ObjectLwM2M>>(
      `/api/resource/lwm2m/page${pageLink.toQuery()}`,
      defaultHttpOptionsFromConfig(config)
    );
  }

  public saveDeviceProfile(deviceProfile: DeviceProfile, config?: RequestConfig): Observable<DeviceProfile> {
    return this.http.post<DeviceProfile>('/api/deviceProfile', deviceProfile, defaultHttpOptionsFromConfig(config));
  }

  public deleteDeviceProfile(deviceProfileId: string, config?: RequestConfig) {
    return this.http.delete(`/api/deviceProfile/${deviceProfileId}`, defaultHttpOptionsFromConfig(config));
  }

  public setDefaultDeviceProfile(deviceProfileId: string, config?: RequestConfig): Observable<DeviceProfile> {
    return this.http.post<DeviceProfile>(`/api/deviceProfile/${deviceProfileId}/default`, defaultHttpOptionsFromConfig(config));
  }

  public getDefaultDeviceProfileInfo(config?: RequestConfig): Observable<DeviceProfileInfo> {
    return this.http.get<DeviceProfileInfo>('/api/deviceProfileInfo/default', defaultHttpOptionsFromConfig(config));
  }

  public getDeviceProfileInfo(deviceProfileId: string, config?: RequestConfig): Observable<DeviceProfileInfo> {
    return this.http.get<DeviceProfileInfo>(`/api/deviceProfileInfo/${deviceProfileId}`, defaultHttpOptionsFromConfig(config));
  }

  public getDeviceProfileInfos(pageLink: PageLink, transportType?: DeviceTransportType,
                               config?: RequestConfig): Observable<PageData<DeviceProfileInfo>> {
    let url = `/api/deviceProfileInfos${pageLink.toQuery()}`;
    if (isDefinedAndNotNull(transportType)) {
      url += `&transportType=${transportType}`;
    }
    return this.http.get<PageData<DeviceProfileInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getDeviceProfileDevicesAttributesKeys(deviceProfileId?: string, config?: RequestConfig): Observable<Array<string>> {
    let url = `/api/deviceProfile/devices/keys/attributes`;
    if (isDefinedAndNotNull(deviceProfileId)) {
      url += `?deviceProfileId=${deviceProfileId}`;
    }
    return this.http.get<Array<string>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getDeviceProfileDevicesTimeseriesKeys(deviceProfileId?: string, config?: RequestConfig): Observable<Array<string>> {
    let url = `/api/deviceProfile/devices/keys/timeseries`;
    if (isDefinedAndNotNull(deviceProfileId)) {
      url += `?deviceProfileId=${deviceProfileId}`;
    }
    return this.http.get<Array<string>>(url, defaultHttpOptionsFromConfig(config));
  }

}
