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

import { Component, forwardRef, Inject, Input } from '@angular/core';
import { ControlValueAccessor, FormBuilder, FormGroup, NG_VALUE_ACCESSOR, Validators } from '@angular/forms';
import {
  DEFAULT_CLIENT_HOLD_OFF_TIME,
  DEFAULT_ID_SERVER,
  DEFAULT_PORT_BOOTSTRAP_NO_SEC,
  DEFAULT_PORT_SERVER_NO_SEC,
  KEY_REGEXP_HEX_DEC,
  LEN_MAX_PUBLIC_KEY_RPK,
  LEN_MAX_PUBLIC_KEY_X509,
  SECURITY_CONFIG_MODE,
  SECURITY_CONFIG_MODE_NAMES,
  ServerSecurityConfig
} from './lwm2m-profile-config.models';
import { coerceBooleanProperty } from '@angular/cdk/coercion';
import { WINDOW } from '@core/services/window.service';
import { pairwise, startWith } from 'rxjs/operators';
import { DeviceProfileService } from '@core/http/device-profile.service';

// @dynamic
@Component({
  selector: 'tb-profile-lwm2m-device-config-server',
  templateUrl: './lwm2m-device-config-server.component.html',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => Lwm2mDeviceConfigServerComponent),
      multi: true
    }
  ]
})

export class Lwm2mDeviceConfigServerComponent implements ControlValueAccessor {

  private requiredValue: boolean;
  private disabled = false;

  valuePrev = null;
  serverFormGroup: FormGroup;
  securityConfigLwM2MType = SECURITY_CONFIG_MODE;
  securityConfigLwM2MTypes = Object.keys(SECURITY_CONFIG_MODE);
  credentialTypeLwM2MNamesMap = SECURITY_CONFIG_MODE_NAMES;
  lenMinServerPublicKey = 0;
  lenMaxServerPublicKey = LEN_MAX_PUBLIC_KEY_RPK;
  currentSecurityMode = null;

  @Input()
  bootstrapServerIs: boolean;

  get required(): boolean {
    return this.requiredValue;
  }

  @Input()
  set required(value: boolean) {
    this.requiredValue = coerceBooleanProperty(value);
  }

  constructor(public fb: FormBuilder,
              private deviceProfileService: DeviceProfileService,
              @Inject(WINDOW) private window: Window) {
    this.serverFormGroup = this.initServerGroup();
    this.serverFormGroup.get('securityMode').valueChanges.pipe(
      startWith(null),
      pairwise()
    ).subscribe(([previousValue, currentValue]) => {
      if (previousValue === null || previousValue !== currentValue) {
        this.getLwm2mBootstrapSecurityInfo(currentValue);
        this.updateValidate(currentValue);
        this.serverFormGroup.updateValueAndValidity();
      }

    });
    this.serverFormGroup.valueChanges.subscribe(value => {
      if (this.disabled !== undefined && !this.disabled) {
        this.propagateChangeState(value);
      }
    });
  }

  private updateValueFields = (serverData: ServerSecurityConfig): void => {
    serverData.bootstrapServerIs = this.bootstrapServerIs;
    this.serverFormGroup.patchValue(serverData, {emitEvent: false});
    this.serverFormGroup.get('bootstrapServerIs').disable();
    const securityMode = this.serverFormGroup.get('securityMode').value as SECURITY_CONFIG_MODE;
    this.updateValidate(securityMode);
  }

  private updateValidate = (securityMode: SECURITY_CONFIG_MODE): void => {
    switch (securityMode) {
      case SECURITY_CONFIG_MODE.NO_SEC:
        this.setValidatorsNoSecPsk();
        break;
      case SECURITY_CONFIG_MODE.PSK:
        this.setValidatorsNoSecPsk();
        break;
      case SECURITY_CONFIG_MODE.RPK:
        this.lenMinServerPublicKey = LEN_MAX_PUBLIC_KEY_RPK;
        this.lenMaxServerPublicKey = LEN_MAX_PUBLIC_KEY_RPK;
        this.setValidatorsRpkX509();
        break;
      case SECURITY_CONFIG_MODE.X509:
        this.lenMinServerPublicKey = 0;
        this.lenMaxServerPublicKey = LEN_MAX_PUBLIC_KEY_X509;
        this.setValidatorsRpkX509();
        break;
    }
    this.serverFormGroup.updateValueAndValidity();
  }

  private setValidatorsNoSecPsk = (): void => {
    this.serverFormGroup.get('serverPublicKey').setValidators([]);
  }

  private setValidatorsRpkX509 = (): void => {
    this.serverFormGroup.get('serverPublicKey').setValidators([Validators.required,
      Validators.pattern(KEY_REGEXP_HEX_DEC),
      Validators.minLength(this.lenMinServerPublicKey),
      Validators.maxLength(this.lenMaxServerPublicKey)]);
  }

  writeValue(value: ServerSecurityConfig): void {
    if (value) {
      this.updateValueFields(value);
    }
  }

  private propagateChange = (v: any) => {};

  registerOnChange(fn: any): void {
    this.propagateChange = fn;
  }

  private propagateChangeState = (value: any): void => {
    if (value !== undefined) {
      if (this.valuePrev === null) {
        this.valuePrev = 'init';
      } else if (this.valuePrev === 'init') {
        this.valuePrev = value;
      } else if (JSON.stringify(value) !== JSON.stringify(this.valuePrev)) {
        this.valuePrev = value;
        if (this.serverFormGroup.valid) {
          this.propagateChange(value);
        } else {
          this.propagateChange(null);
        }
      }
    }
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    this.valuePrev = null;
    if (isDisabled) {
      this.serverFormGroup.disable({emitEvent: false});
    } else {
      this.serverFormGroup.enable({emitEvent: false});
    }
  }

  registerOnTouched(fn: any): void {
  }

  private initServerGroup = (): FormGroup => {
    const port = this.bootstrapServerIs ? DEFAULT_PORT_BOOTSTRAP_NO_SEC : DEFAULT_PORT_SERVER_NO_SEC;
    return this.fb.group({
      host: [this.window.location.hostname, this.required ? Validators.required : ''],
      port: [port, this.required ? Validators.required : ''],
      bootstrapServerIs: [this.bootstrapServerIs, ''],
      securityMode: [this.fb.control(SECURITY_CONFIG_MODE.NO_SEC)],
      serverPublicKey: ['', this.required ? Validators.required : ''],
      clientHoldOffTime: [DEFAULT_CLIENT_HOLD_OFF_TIME, this.required ? Validators.required : ''],
      serverId: [DEFAULT_ID_SERVER, this.required ? Validators.required : ''],
      bootstrapServerAccountTimeout: ['', this.required ? Validators.required : ''],
    });
  }

  private getLwm2mBootstrapSecurityInfo = (mode: string): void => {
    this.deviceProfileService.getLwm2mBootstrapSecurityInfo(mode, this.serverFormGroup.get('bootstrapServerIs').value).subscribe(
      (serverSecurityConfig) => {
        this.serverFormGroup.patchValue({
            host: serverSecurityConfig.host,
            port: serverSecurityConfig.port,
            serverPublicKey: serverSecurityConfig.serverPublicKey,
            clientHoldOffTime: serverSecurityConfig.clientHoldOffTime,
            serverId: serverSecurityConfig.serverId,
            bootstrapServerAccountTimeout: serverSecurityConfig.bootstrapServerAccountTimeout
          },
          {emitEvent: true});
      }
    );
  }
}
