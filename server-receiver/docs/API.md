# CallMonitor receiver API v1 / server 0.1.0

HTTPS required. No redirect following for authenticated uploads. One per-device
Bearer credential. `tenant_id` and `operator_id` are resolved on the server from
the authenticated device. No token plaintext in server DB/config/logs.

## POST /api/v1/calls

multipart/form-data, file part `audio` with `.ogg` filename. Maximum 100 MiB.

Required scalar parts: call_id (UUID generated once), device_id, direction
(in/out), remote_number (empty string when unknown), started_at (RFC3339 with
timezone and at most 6 fractional second digits), duration_ms, app_build,
audio_sha256, audio_bytes, codec=opus, sample_rate=48000, channels=2,
channel_layout=stereo. Optional: original_filename, operator_id, left_role,
right_role. If optional identity/role fields are supplied they must match the
authenticated server device profile. Reserved build-19 device roles are
left_role=operator, right_role=client. Strings are bounded and validated.

OGG checked for framing, page sequence/CRC, a single complete logical stream,
OpusHead/OpusTags, mapping family 0, 2 channels, EOS. Does not decode audio.
Opus granule clock is 48 kHz; the OpusHead input sample-rate field is not a
requirement on decoder output rate. Measured `audio_duration_ms` is kept
separately from client-supplied `duration_ms`.

Idempotency scope: UNIQUE(tenant_id, call_id); same device + same SHA returns
original server_call_id and received_at. Different file SHA or another device
using an existing call_id in the same tenant yields 409. The same UUID in a
different tenant is independent. Same audio bytes with different call_ids are
allowed. Metadata of already committed calls is not overwritten on retry.

201 new / 200 duplicate:

    {"ok":true,"call_id":"...","server_call_id":123,"stored":true,"duplicate":false,"received_at":"...Z"}

Errors: 400 invalid metadata/file; 401 invalid or disabled token/tenant;
403 device/operator mismatch; 409 call conflict; 413 size policy; 426 HTTPS
required; 503 temporary storage/DB/internal failure. Error envelope includes
`ok:false`, `error` machine code, and `retryable` boolean. 5xx retry with backoff;
401/403 stop until provisioning changes; 409 requires investigation; 400/413
keep local original and expose actionable status. On 200/201 Android must
check JSON, ok, stored and matching call_id before marking uploaded.

Private path is generated only from server-controlled tenant/device IDs and
validated UUID, never from supplied filename. The path is deterministic for
process-crash recovery. File is fsynced, moved before INSERT and DB commit
under a short write transaction. Orphan of same hash is adopted on retry;
orphan of another hash is not overwritten. Missing committed file may be
repaired by exact retransmission; corrupt committed file returns 503 and is
preserved. A post-commit lost HTTP response is handled by duplicate retry.

## GET /health.php or /api/v1/health

Public HTTPS JSON reports service/version/readiness and UTC time only.
Does not expose paths, database counters, tokens or phone numbers.

## GET /api/v1/status

Admin Bearer required. Global administrative view, not a tenant client view.
Counters, device status, last 30 calls with masked phone numbers; no paths or
tokens. /status.php provides a form that POSTs admin token in the HTTPS body,
does not put tokens in URL/cookies/browser local storage, and sends no-store.

Processing jobs are queued as `channel_split / waiting` only. No processor
executes in v0.1.0; no audio is sent to any AI or external service.
