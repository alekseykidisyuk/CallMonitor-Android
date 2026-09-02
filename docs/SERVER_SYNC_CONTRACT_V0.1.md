# CallMonitor server synchronization contract v0.1

This document defines the first Android-to-server contract. It is intentionally narrow: completed carrier-call recordings and their metadata.

## Transport

HTTPS only. The Android application must reject clear-text production endpoints.

Authentication is per enrolled device, not a shared global password. The server issues a revocable device token after enrollment. Tokens are stored in Android private app storage and are never written into recording folders or log files.

Recommended request header:

```text
Authorization: Bearer <device-token>
X-CallMonitor-Device: <device-uuid>
```

## Endpoint

```text
POST /api/v1/calls
Content-Type: multipart/form-data
```

Parts:

- `metadata`: UTF-8 JSON, `application/json`
- `audio`: finalized recording file

The `call_id` inside metadata is the idempotency key.

## Metadata v0.1

Required:

```json
{
  "schema_version": 1,
  "call_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "server-issued-uuid",
  "direction": "outgoing",
  "started_at": "2026-09-03T10:15:20+05:00",
  "ended_at": "2026-09-03T10:15:47+05:00",
  "duration_ms": 27000,
  "remote_number": "+998901234567",
  "audio_codec": "opus",
  "audio_sha256": "lowercase-hex",
  "audio_bytes": 145123,
  "app_version": "2.2.0-callmonitor.4"
}
```

Optional:

```json
{
  "contact_name": "Client name from phone",
  "phone_model": "Redmi Note 12",
  "android_version": "15",
  "sim_subscription_id": 1,
  "sim_slot": 0,
  "carrier_name": "Beeline UZ",
  "recording_health": {
    "source": "voice_call",
    "channels": 2,
    "adb_transport": "loopback|wireless",
    "recovered_after_boot": false
  }
}
```

The server normalizes `remote_number` independently. The device-provided number must never be trusted as an organization/customer identifier by itself.

## Success response

New call:

```json
{
  "ok": true,
  "call_id": "...",
  "server_call_id": 12345,
  "audio_sha256": "...",
  "status": "accepted"
}
```

Duplicate retry with identical hash:

```json
{
  "ok": true,
  "call_id": "...",
  "server_call_id": 12345,
  "audio_sha256": "...",
  "status": "already_accepted"
}
```

The Android queue treats both as a final acknowledgement.

## Integrity conflict

If the same `call_id` arrives with a different audio hash, return HTTP 409:

```json
{
  "ok": false,
  "error": "call_id_hash_conflict"
}
```

Android must stop automatic retries for that item and surface the conflict in diagnostics.

## Retry policy

Retry automatically for network errors and HTTP 408/429/5xx.

Suggested schedule:

```text
immediate
+ 1 min
+ 5 min
+ 15 min
+ 1 h
then exponential backoff capped at 6 h
```

The queue survives process death and phone reboot.

Do not retry automatically for authentication failure (401/403), schema validation failure (400/422), or integrity conflict (409). Those states require configuration or operator attention.

## Server processing states

The upload acknowledgement must not wait for transcription or AI analysis.

```text
accepted
 -> stored
 -> transcription_pending
 -> transcribing
 -> transcript_ready
 -> analysis_pending
 -> analyzed
 -> delivered
```

Failures remain explicit (`transcription_failed`, `analysis_failed`, `telegram_failed`) and can be retried independently without re-uploading the audio.

## Telegram

Telegram delivery is downstream from analysis. The Android phone never needs the Telegram bot token.

A Telegram report should reference `server_call_id`/CRM entity and can include:

- caller/customer label;
- direction and duration;
- short summary;
- detected next action and due date;
- escalation flags;
- link to the server/FarmBase call card.

Raw audio is not sent to Telegram by default.

## Retention

Server acknowledgement is not permission to immediately delete the phone copy. Local retention is a separate configurable policy. For the first production version, keep acknowledged local recordings for a safety window (for example 7–30 days) unless storage pressure requires earlier cleanup.
