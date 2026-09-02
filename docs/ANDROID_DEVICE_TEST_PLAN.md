# CallMonitor Android device regression plan

Primary reference device: Redmi Note 12 / HyperOS / Android 15.

The point of this plan is to prove unattended behavior, not merely that a recording can be created once.

## A. Fresh install and pairing

1. Install the current `CallMonitor-Android-test.apk` after removing any APK signed by an older test key.
2. Complete Android permissions.
3. Pair CallMonitor through Wireless Debugging once.
4. Confirm the recorder reaches `Ready to record`.

Pass criteria:

- pairing succeeds on HyperOS without a PC;
- CallMonitor appears as an authorized Wireless Debugging device;
- no Shizuku dependency is used.

## B. Carrier recording with Wi-Fi

1. Disconnect USB completely.
2. Keep Wi-Fi connected.
3. Make an outgoing carrier call for at least 20 seconds with speech from both parties.
4. Play the file.

Pass criteria:

- recording starts automatically;
- both speakers are clearly audible;
- no Google Phone spoken recording announcement is introduced by CallMonitor;
- finalized local file is present.

## C. Carrier recording without Wi-Fi

1. Enable off-Wi-Fi recording and let the loopback transport arm.
2. Turn normal Wi-Fi off; keep only mobile data.
3. Make another carrier call.

Pass criteria:

- recording is created automatically;
- both speakers are audible;
- no PC/USB/Wi-Fi connection is required during the call.

## D. Reboot recovery

1. Leave off-Wi-Fi recording enabled.
2. Turn Wi-Fi off.
3. Reboot the phone.
4. Open CallMonitor only to observe status; do not enter Developer Options.
5. Turn normal Wi-Fi on and connect to any remembered Wi-Fi network.
6. Wait up to two watchdog intervals without manually toggling Wireless Debugging.

Target pass criteria:

- CallMonitor automatically restores the privileged recorder after Wi-Fi becomes available;
- the user is not required to revisit the Wireless Debugging settings page;
- once recovered, Wi-Fi can be turned off and carrier calls still record.

If the ROM refuses automatic Wireless Debugging enable because `WRITE_SECURE_SETTINGS` is unavailable, record that as an explicit OEM limitation and capture diagnostics rather than hiding it.

## E. In-place update/signing regression

This test begins only after one stable-signer CallMonitor APK is installed and paired.

1. Install the next higher CallMonitor CI build **over** the existing app without uninstalling.
2. Do not pair again.
3. Confirm the recording folder, preferences and ADB identity remain present.
4. Make a carrier call.
5. Reboot and repeat recovery test D.

Pass criteria:

- Android accepts the update in-place;
- no pairing reset;
- no loss of local settings/recordings;
- privileged grants that Android normally preserves survive the update.

## F. Failure handling

Verify settings actions never show an endless indeterminate spinner.

In particular:

- disabling off-Wi-Fi mode after reboot when loopback is already gone returns immediately;
- a wedged ADB operation has a bounded timeout;
- UI status reports that recording is unavailable instead of claiming success.
