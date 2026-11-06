# QR and NFC pairing

## Implemented flow

Home retains all three manual connection cards and adds **扫描相机二维码** and **NFC 配对**.
QR scanning uses the embedded ZXing decoder (4.3.0, Apache-2.0), no Play Services download,
image storage or external scanner application. Camera permission belongs to the scanning Activity.
The scanner releases its camera on pause; Back cancels scanning.

Both inputs produce transient `PairingCredentials`, then request a WPA2 local-only Wi-Fi network
through Android `WifiNetworkSpecifier`. `CompanionController.connect()` owns the request via
`PairingConnection` until failure, cancellation, network loss, session end, or replacement.
Only `onAvailable`'s Network is used for SSDP and CameraHttp. No process-wide network binding is
performed. Mobile data remains the default internet route where the platform supports it;
on single-STA phones Android may replace the existing Wi-Fi. Internet/Wi-Fi concurrency is not guaranteed.

Wi-Fi approval/connection and REMOTE readiness are separate states. Discovery retries up to three
four-second SSDP rounds with 750 ms gaps. Credentials never override the manual descriptor URL
setting for other modes; paired discovery deliberately ignores that setting. After finding exactly
one REMOTE service, the existing RemoteCamera initialization, SessionIdentity, queues and UI navigation
are reused. API failure never reports pairing/session success.

The 60-second Wi-Fi timeout, rejection and authentication/unreachable failures release the request.
Android's public callback does not reliably distinguish user refusal from wrong password or missing
hotspot; the error lists these possibilities rather than inventing a diagnosis. Home **取消连接** also
aborts an outstanding request or discovery. End the current session before scanning/touching another
camera. Late callbacks from a released request cannot connect or terminate a newer session.

An approval Activity can pause/stop MainActivity. The request belongs to the application controller,
not a Composable or Activity; it survives that transition and configuration recreation. Pending
credentials/permission continuation are memory-only and discarded on process death. The OS owns and
releases requests on process death. NFC foreground dispatch is enabled only while MainActivity is
resumed and disabled on pause. Duplicate NFC delivery during a pending/active session is ignored with
an explanation. No automatic retry is started when an app returns from the background. A platform
that drops the local network triggers session disconnection and requires a new user request.

## APK evidence (supplied Imaging Edge Mobile 7.8.6)

The APKS contains base.apk plus arm64-v8a, ms, zh, xhdpi and xxxhdpi splits. The base DEX files
contain the protocol logic. Analysis used DEX instruction inspection and Androguard DAD decompilation.
This implementation is independently written; no Sony classes, binaries, assets or decompiled source
are incorporated in the app.

### QR

- `com.sony.playmemories.mobile.qr.data.QrDataUtil.getVersion/getData/getSsid/removeEscapeCharacter`
  confirms version 1, S/P/C/M field markers, `DIRECT-` + S + `:` + C, and escaping `; , : \\`.
- `qr.QrReaderCallbackController.checkQrString` validates S length four and P length eight before
  constructing WifiQrData. Its WifiQrData stores version, prefix, friendly name and password;
  `getSsid()` delegates to QrDataUtil. The observed ILCE-7RM2 SSID matches this rule.
- M is recognized by QrDataUtil but is not used by that WifiQrData construction path. No evidence
  here establishes it as the AP BSSID or an application-level authentication token. Companion validates
  an optional 12-hex M but neither connects by that address nor treats it as camera identity.
- Companion supports W01 only, rejects duplicate/unknown fields, truncated/invalid escapes,
  empty/malformed WPA2 credentials and SSIDs over 32 UTF-8 bytes. It accepts standard WPA2 printable
  passphrases of 8–63 characters rather than imposing Sony's UI-level exact eight-character check.
- The APK also recognizes a ten-character legacy QR branch, implemented by an optimized class.
  Its semantics were not established and Companion intentionally rejects it, as well as W02+.
  Newer Bluetooth/Creators' App pairing formats are outside this feature.

### NFC

`nfc.NfcUtil.enableDispatchUncatchable` registers `NDEF_DISCOVERED` for **application/x-sony-pmm**.
`nfc.NdefDescription.getValueFromNfcIntent` checks this MIME and reads its payload; the
`EnumNdefInfo` byte constants establish:

| Tag bytes | Meaning used here | Value |
| --- | --- | --- |
| 00 01 | Version | 10 hex (one byte) |
| 10 00 | Full SSID | UTF-8 text |
| 10 01 | Password | UTF-8 / printable WPA2 text |
| 10 04 | Wi-Fi configuration availability | Optional; 01 if present |

Each field is two tag bytes, two length bytes, then its value. The APK computes length as
`unsigned(length[0]) * 10 + unsigned(length[1])`: these are binary numeric bytes, **not ASCII decimal
characters or a big-endian 16-bit length**. Version and Wi-Fi setting are rendered by the APK as hex
strings and compared with `10` and `01`. Companion performs equivalent byte comparisons and strict
bounds/duplicate checks; it skips unknown extension fields. It does not interpret unverified optional
static IP, WPS or descriptor fields or copy an arbitrary descriptor URL out of a tag.

This is standard NDEF transport with a Sony private MIME payload, not a WSC credential or Connection
Handover implementation. The identified official path contains full SSID/password, so it needs no
invented identifier-to-password exchange. `wificonnection.connection.WifiConnectionLaterThanQ.connect`
uses WifiNetworkSpecifier, WPA2 passphrase and requestNetwork; a BSSID may be used when separately
available and validated, but Companion does not infer one from QR M. No additional Sony application
pairing handshake was established in this QR/NFC-to-Wi-Fi path. Existing REMOTE API initialization
still runs afterwards.

The official APK's support for this format is verified statically. **The actual ILCE-7RM2 NFC record,
RF/tag technology and wake/launch behavior have not been captured or tested on hardware.** Companion
supports the documented private NDEF payload when delivered by Android, including foreground dispatch
and a narrow MIME launch filter. It does not use Android Beam, emulate a handover, write a tag, or claim
that any NFC detection equals successful pairing. WSC, handover-only, identifier-only, unsupported-version,
incomplete or unreadable tags require QR/manual connection. An AAR may route a cold tap to Sony's app
on some phones; open Companion first. The NFC button explains hardware availability, disabled NFC and
the fallback; it does not claim a request has succeeded.

## Android versions and permissions

| Android | Pairing behavior |
| --- | --- |
| 10–11 (29–30) | WifiNetworkSpecifier; foreground coarse/fine permission request, precise permission required. Check Wi-Fi and system location settings if unavailable. |
| 12 / 12L (31–32) | Request coarse and fine together; approximate-only is insufficient for the pairing gate. |
| 13–16 (33–36) | Nearby Wi-Fi devices permission (`neverForLocation`); scanner separately requests Camera. No location sampling for pairing. |

CHANGE_WIFI_STATE and CHANGE_NETWORK_STATE are normal manifest permissions. Existing precise/coarse
location declarations remain for the independent opt-in capture geotagging feature. Manual connection
permission policy is unchanged. Android 16's optional local-network restriction experiment may also
require Nearby devices; Android 17 enforcement is outside the target SDK 36 scope.

The Android system controls network approval and may remember an app/AP approval. No root, saved-network
mutation, automatic Wi-Fi toggle or bypass of system confirmation is used. No background location
permission is requested. Pairing should be initiated with the app visible. The platform can revoke
requests or networks after backgrounding; detection/cleanup are supported, indefinite background
pairing is not promised.

## Validation and device checklist

Automated coverage includes parser validation, escapes and redacted diagnostics; binary-decimal NFC
fixtures, malformed/incomplete/unsupported tags; fake backend approval/rejection/cancel/timeout/loss,
synchronous and late callbacks; bounded SSDP retries; emulator tests for Wi-Fi-before-REMOTE ordering,
existing REMOTE initialization, network lease release, initialization failure, permission mapping,
NDEF adapter errors and presence of manual/QR/NFC UI entries. These tests simulate callbacks and local
HTTP endpoints. They do not exercise a real system Wi-Fi approval dialog, physical NFC or Sony hotspot.
Check the commit's Android Debug and Android UI Actions results for actual automated outcomes.

### QR on the real camera

1. Open Smart Remote Control on the camera and display its QR screen. Leave phone Wi-Fi enabled.
2. Open Home → 扫描相机二维码; grant Camera and the requested pairing permission.
3. Scan the camera QR and accept Android's Wi-Fi confirmation. Verify the status progresses from
   waiting for Android, to Wi-Fi connected/discovery, to REMOTE viewfinder. Take a photo.
4. End the session; repeat scanning. Also test Back in scanner, refusal in the Wi-Fi dialog,
   **取消连接** while waiting, camera powered off/timeout, and camera hotspot disappearing.
5. Repeat while rotating the phone, switching briefly to system UI and returning. Verify no duplicate
   requests, false connected state, old callback overwrites or password/raw payload in exported logs.
6. Verify original manual REMOTE, JPEG and COMPUTER connections on their respective camera modes.

### NFC on the real camera

1. Enable NFC on both devices. Open Companion and camera Smart Remote Control first.
2. Touch the phone's NFC antenna to the camera N mark for about two seconds. Android should deliver
   the Sony NDEF record; grant any requested Wi-Fi permission and accept the system confirmation.
3. Require the same discovery → REMOTE → live view/photo result as QR, then test repeat touches,
   disconnect/reconnect, leaving/returning to the Activity, and NFC disabled.
4. If no record arrives, note phone model/Android version and whether Sony's app starts instead.
   If a record is rejected, the minimum extra evidence is the NDEF record TNF/type and raw payload
   captured with an NFC tag reader (contains Wi-Fi password: share privately). This establishes the
   actual camera format/transport; ordinary app logs intentionally never contain the payload.

## Platform and dependency references

- https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap
- https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/develop/connectivity/nfc/advanced-nfc
- https://github.com/journeyapps/zxing-android-embedded (Apache-2.0)
