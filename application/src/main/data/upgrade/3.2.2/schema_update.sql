--
-- Copyright © 2016-2021 The Thingsboard Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE IF NOT EXISTS edge (
    id uuid NOT NULL CONSTRAINT edge_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    additional_info varchar,
    customer_id uuid,
    root_rule_chain_id uuid,
    type varchar(255),
    name varchar(255),
    label varchar(255),
    routing_key varchar(255),
    secret varchar(255),
    edge_license_key varchar(30),
    cloud_endpoint varchar(255),
    search_text varchar(255),
    tenant_id uuid,
    CONSTRAINT edge_name_unq_key UNIQUE (tenant_id, name),
    CONSTRAINT edge_routing_key_unq_key UNIQUE (routing_key)
    );

CREATE TABLE IF NOT EXISTS edge_event (
    id uuid NOT NULL CONSTRAINT edge_event_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    edge_id uuid,
    edge_event_type varchar(255),
    edge_event_uid varchar(255),
    entity_id uuid,
    edge_event_action varchar(255),
    body varchar(10000000),
    tenant_id uuid,
    ts bigint NOT NULL
    );

CREATE TABLE IF NOT EXISTS resource (
    id uuid NOT NULL CONSTRAINT resource_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_key varchar(255) NOT NULL,
    search_text varchar(255),
    file_name varchar(255) NOT NULL,
    data varchar,
    CONSTRAINT resource_unq_key UNIQUE (tenant_id, resource_type, resource_key)
);

CREATE TABLE IF NOT EXISTS firmware (
    id uuid NOT NULL CONSTRAINT firmware_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    device_profile_id uuid,
    type varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    version varchar(255) NOT NULL,
    file_name varchar(255),
    content_type varchar(255),
    checksum_algorithm varchar(32),
    checksum varchar(1020),
    data oid,
    data_size bigint,
    additional_info varchar,
    search_text varchar(255),
    CONSTRAINT firmware_tenant_title_version_unq_key UNIQUE (tenant_id, title, version)
);

ALTER TABLE device_profile
    ADD COLUMN IF NOT EXISTS firmware_id uuid,
    ADD COLUMN IF NOT EXISTS software_id uuid;

ALTER TABLE device
    ADD COLUMN IF NOT EXISTS firmware_id uuid,
    ADD COLUMN IF NOT EXISTS software_id uuid;

DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_firmware_device_profile') THEN
            ALTER TABLE device_profile
                ADD CONSTRAINT fk_firmware_device_profile
                    FOREIGN KEY (firmware_id) REFERENCES firmware(id);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_software_device_profile') THEN
            ALTER TABLE device_profile
                ADD CONSTRAINT fk_software_device_profile
                    FOREIGN KEY (firmware_id) REFERENCES firmware(id);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_firmware_device') THEN
            ALTER TABLE device
                ADD CONSTRAINT fk_firmware_device
                    FOREIGN KEY (firmware_id) REFERENCES firmware(id);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_software_device') THEN
            ALTER TABLE device
                ADD CONSTRAINT fk_software_device
                    FOREIGN KEY (firmware_id) REFERENCES firmware(id);
        END IF;
    END;
$$;

