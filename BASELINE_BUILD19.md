# CallMonitor Android — build 19 baseline

This preservation branch starts at the exact build-19 source commit. This document is the only added change; no Android source, workflow, capture, recovery or encoder change is intended. A Git branch is mutable: use the exact source commit and artifact digests below as identity references. No protected tag or release has been created.

## Identity

- Source/workflow commit: `5031bbc76b67f60aa0ab3a46abe9608bb7db368b`
- Stereo patch commit (anchor reference): `fb61d7b6678188d4ea1eb71b3aa8a5f2957f8bb6`
- Actions run: https://github.com/alekseykidisyuk/CallMonitor-Android/actions/runs/34039765901
- Artifact ID: `9991384504`
- Artifact name: `CallMonitor-Android-test`
- ZIP SHA-256, verified against GitHub artifact metadata: `2c29b2eedab03127882158e49da253fb43270e32ef6f19a1a4c6154e2d9a496f`
- GitHub artifact expiry reported at verification: `2026-12-05T14:37:49Z`; artifact is not permanent storage.
- Installed filename, per supplied anchor: `CallMonitor-Android-build19.apk`
- APK bytes, per supplied anchor (not independently downloaded in this verification): `85730711`
- APK SHA-256, per supplied anchor: `f44b54fdcdfd783c7cd98889732bb757eaa36083b9767acd9f45f36fbca220c1`
- Actual stereo patch path verified from workflow: `.github/scripts/build19_stereo_voicecall_patch.py` (anchor has an extra underscore in this filename).

## Device results carried forward from supplied anchor

Xiaomi Redmi Note 12 / 23021RAAEG, HyperOS 2.0.208.0.VMTMIXM, Android 15. Digital SIM VOICE_CALL recording without root; stereo Opus, LEFT=operator and RIGHT=remote party confirmed for incoming and outgoing test calls. These hardware results were reported in the anchor, not re-tested by this repository verification. Recovery after reboot requires Wi-Fi availability; do not promise cold 4G-only recovery. Mapping is specific to tested device/capture profile.

## Frozen behavior and next stage

- Preserve capture, encoding and HyperOS recovery during receiver/upload implementation.
- Keep package `com.baba.callvault`, existing app data, pairing keys, Accessibility state and signing identity. Update install only; do not uninstall.
- Preserve upstream GPLv3 and Section 7 notices.
- First implement standalone PHP/SQLite CallMonitor receiver with private audio, tenant isolation, per-device auth, server SHA/size verification and idempotent uploads.
- Only after receiver acceptance implement build 20 durable upload queue; then transcription/analysis and external integrations.
- Never commit device tokens, production secrets or signing private keys to this repository.

## Signing issue observed in current workflow

The workflow restores `~/.android/debug.keystore` from Actions cache `callmonitor-android-test-signer-v1`, and generates a new key when that file is missing. Its certificate comparison only compares the APK against the key present in the current run; it does not pin build-19 identity. Therefore cache loss could silently produce an incompatible update signer.

Before build 20: recover and preserve the existing signer in an appropriate private secret store, obtain and pin the build-19 certificate SHA-256, and fail the update build if the signer is missing or mismatched. Do not replace the current signer or expose its private key. No signing/workflow change was made in this baseline step.
