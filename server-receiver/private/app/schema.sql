PRAGMA foreign_keys=ON;
PRAGMA journal_mode=DELETE;
PRAGMA synchronous=FULL;
CREATE TABLE tenants (tenant_id TEXT PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN(0,1)), created_at TEXT NOT NULL);
CREATE TABLE operators (operator_id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants, name TEXT NOT NULL, UNIQUE(tenant_id,operator_id));
CREATE TABLE devices (
 device_id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants, operator_id TEXT,
 token_hash TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN(0,1)),
 model TEXT NOT NULL, channels INTEGER NOT NULL DEFAULT 2 CHECK(channels=2),
 left_role TEXT NOT NULL DEFAULT 'operator', right_role TEXT NOT NULL DEFAULT 'client',
 created_at TEXT NOT NULL, last_seen_at TEXT,
 UNIQUE(tenant_id,device_id), FOREIGN KEY(tenant_id,operator_id) REFERENCES operators(tenant_id,operator_id)
);
CREATE TABLE calls (
 server_call_id INTEGER PRIMARY KEY AUTOINCREMENT, call_id TEXT NOT NULL,
 tenant_id TEXT NOT NULL, device_id TEXT NOT NULL, operator_id TEXT,
 direction TEXT NOT NULL CHECK(direction IN('in','out')), remote_number TEXT,
 started_at TEXT NOT NULL, duration_ms INTEGER NOT NULL, audio_duration_ms INTEGER NOT NULL,
 app_build INTEGER NOT NULL, audio_sha256 TEXT NOT NULL, audio_bytes INTEGER NOT NULL,
 codec TEXT NOT NULL, sample_rate INTEGER NOT NULL, channels INTEGER NOT NULL,
 channel_layout TEXT NOT NULL, left_role TEXT NOT NULL, right_role TEXT NOT NULL,
 original_filename TEXT, relative_path TEXT NOT NULL UNIQUE, received_at TEXT NOT NULL,
 processing_status TEXT NOT NULL DEFAULT 'received',
 UNIQUE(tenant_id,call_id), FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,device_id),
 FOREIGN KEY(tenant_id,operator_id) REFERENCES operators(tenant_id,operator_id)
);
CREATE INDEX calls_sha ON calls(audio_sha256);
CREATE INDEX calls_device_received ON calls(device_id,received_at);
CREATE TABLE processing_jobs (
 job_id INTEGER PRIMARY KEY AUTOINCREMENT, server_call_id INTEGER NOT NULL REFERENCES calls,
 stage TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'waiting', attempts INTEGER NOT NULL DEFAULT 0,
 last_error TEXT, created_at TEXT NOT NULL, UNIQUE(server_call_id,stage)
);
PRAGMA user_version=1;
