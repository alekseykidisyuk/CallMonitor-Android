# CallMonitor fork notice

CallMonitor Android is a modified fork of **CallVault** by the CallVault authors.

Upstream project: `madkongo/CallVault`

The upstream source is licensed under the GNU General Public License v3 or later, with the additional notices/terms present in the upstream repository. CallMonitor preserves the upstream `LICENSE` and `NOTICE.md` files. Modified source files retain their upstream copyright/license headers where applicable.

## CallMonitor-specific work

The fork currently focuses on:

- Android carrier-call recording as an input to a separate call-intelligence system;
- Xiaomi/HyperOS embedded-ADB compatibility where Wireless Debugging is advertised on the WLAN address rather than loopback;
- bounded ADB/offline-mode operations so the UI cannot wait indefinitely on a stale socket;
- stable CI test signing to preserve pairing and privileged grants across in-place development updates;
- disabling the upstream CallVault update channel so an upstream APK cannot replace the fork;
- future HTTPS synchronization of finalized recordings to the CallMonitor server for transcription, structured analysis, CRM/FarmBase matching and optional Telegram reporting.

The upstream CallVault project is not responsible for CallMonitor modifications or behavior.
