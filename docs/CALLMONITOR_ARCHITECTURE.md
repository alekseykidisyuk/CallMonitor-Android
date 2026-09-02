# CallMonitor architecture

CallMonitor is a fork of CallVault focused on reliable carrier-call capture on Android and integration with a server-side call-intelligence pipeline.

## Product boundary

The Android application is deliberately kept responsible for the tasks that must happen on the phone:

1. detect incoming and outgoing carrier calls;
2. capture both sides of the call without root where the device/ROM permits it;
3. save the audio locally first;
4. attach deterministic call metadata;
5. keep a durable upload queue;
6. synchronize completed recordings to the CallMonitor server over HTTPS;
7. continue recording when the server or Internet connection is unavailable.

The Android client is **not** the canonical transcription or analytics engine. Upstream CallVault's on-device Whisper/Gemma features may remain in the source during early stabilization, but the CallMonitor production flow does not depend on them.

## Server responsibility

The server owns the post-call pipeline:

```text
Android recorder
    -> durable upload queue
    -> CallMonitor HTTPS API
    -> audio/object storage + call database
    -> transcription worker
    -> structured conversation analysis
    -> FarmBase/CRM matching
    -> Telegram and web reports
```

This separation allows the Android recorder to remain small and reliable while transcription/analysis models can change without replacing the phone application.

## Call identity

Every completed call receives a UUID generated on the Android device. The UUID is the idempotency key for every retry and is never derived from the file name.

Minimum metadata contract:

```json
{
  "call_id": "uuid",
  "device_id": "server-issued device UUID",
  "direction": "incoming|outgoing",
  "started_at": "ISO-8601 with timezone",
  "ended_at": "ISO-8601 with timezone",
  "duration_ms": 27000,
  "remote_number_e164": "+998...",
  "contact_name": "optional local contact label",
  "audio_codec": "opus",
  "audio_sha256": "hex",
  "audio_bytes": 145123,
  "app_version": "2.2.0-callmonitor.N",
  "recording_result": "complete"
}
```

Later fields can include SIM slot/subscription, operator/manager identity, source phone model, recording-health telemetry and speaker-channel information when reliably available.

## Local-first invariant

A call is considered captured only after the finalized audio file is durably present on the phone. Server synchronization is a separate state machine.

Recommended states:

```text
RECORDING
  -> LOCAL_READY
  -> QUEUED
  -> UPLOADING
  -> UPLOADED

failure during upload -> QUEUED with retry metadata
```

The recorder must never delete or overwrite a local file merely because upload failed.

## Idempotent synchronization

The server treats `call_id` as unique. Repeated uploads of the same call are safe:

- same `call_id` + same SHA-256: return the existing acknowledgement;
- same `call_id` + different SHA-256: reject and flag an integrity conflict;
- an interrupted multipart upload never creates a completed call record until the audio hash is verified.

Android deletes/archives local recordings only under an explicit retention policy after a positive server acknowledgement.

## Transcription and analysis

Transcription is implemented behind a server-side provider interface. This lets CallMonitor use, depending on deployment requirements:

- OpenAI speech-to-text/API processing;
- a self-hosted Whisper-compatible worker;
- another approved engine later.

The normalized transcript is stored separately from the raw provider response so changing providers does not change the rest of the product.

Suggested analysis output:

```json
{
  "summary": "short factual summary",
  "customer_intent": "...",
  "result": "...",
  "next_action": "...",
  "next_action_due_at": "optional datetime",
  "promises_by_manager": ["..."],
  "customer_requests": ["..."],
  "products_or_equipment": ["..."],
  "prices_and_terms": ["..."],
  "risk_flags": ["..."],
  "quality_flags": ["..."],
  "crm_match": {
    "entity_type": "contact|organization|unknown",
    "entity_id": "optional internal id",
    "confidence": 0.0
  }
}
```

## Telegram reporting

Telegram is an output channel, never the system of record.

Possible delivery modes:

- one report after every processed call;
- only calls containing an action/risk condition;
- manager-specific channels;
- daily/shift digest;
- escalation when a promised follow-up becomes overdue.

The Telegram message should contain a short summary and a link/identifier back to the authoritative CallMonitor/FarmBase record. Raw audio should not be sent to Telegram by default.

## FarmBase integration

FarmBase matching starts with normalized telephone numbers and can later use organization/contact relationships. The call record remains independent from FarmBase so the same CallMonitor deployment can serve other CRM systems or operate as a standalone product.

## Android transport requirements

The fork must preserve the proven non-root recording path while hardening ADB lifecycle behavior:

- one-time pairing;
- OEM-compatible Wireless Debugging resolution (including Xiaomi/HyperOS WLAN-only binding);
- no unbounded ADB waits;
- stable application signing during testing so ADB identity and privileged grants survive upgrades;
- post-boot self-recovery whenever Android permits it;
- clear health state when a ROM requires user intervention.

## Scope order

1. Stabilize carrier-call recording and reboot/off-Wi-Fi lifecycle.
2. Add durable Android upload queue and server enrollment.
3. Implement ingestion API and storage.
4. Add transcription worker.
5. Add structured analysis and Telegram delivery.
6. Add FarmBase/CRM linkage and management UI.
