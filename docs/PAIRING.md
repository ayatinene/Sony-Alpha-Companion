# QR and NFC pairing

## Implemented flow

Home retains all three manual connection cards and adds **扫描相机二维码** and **NFC 配对**.
QR scanning uses the embedded ZXing decoder (4.3.0, Apache-2.0), no Play Services download,
image storage or external scanner application. Camera permission belongs to the scanning Activity.
The scanner releases its camera on pause; Back cancels scanning. Scanning is portrait-locked so rotating a phone does not stretch the QR frame into the library's landscape rectangle.

Both inputs produce transient `PairingCredentials`, with a QR/NFC source marker, then request a WPA2 local-only Wi-Fi network
through Android `WifiNetworkSpecifier`. `CompanionController.connect()` owns the request via
`PairingConnection` until failure, cancellation, network loss, session end, or replacement.
Only `onAvailable`'s Network is used for SSDP and CameraHttp. No process-wide network binding is
performed. Mobile data remains the default internet route where the platform supports it;
on single-STA phones Android may replace the existing Wi-Fi. Internet/Wi-Fi concurrency is not guaranteed.

Wi-Fi approval/connection and REMOTE readiness are separate states. Discovery retries up to three
four-second SSDP rounds with 750 ms gaps and a 20-second overall paired-discovery limit. Credentials never override the manual descriptor URL
setting for other modes; paired discovery deliberately ignores that setting. QR continues to select REMOTE. NFC inspects current SSDP-advertised services: ScalarWebAPI selects
REMOTE and ContentDirectory selects JPEG. Ambiguous/missing services produce a clear error rather than
choosing from SSID/password or probing an inactive fallback descriptor. Once selected, the existing
RemoteCamera or SmartphoneTransfer initialization, SessionIdentity, queues and UI navigation are reused.
NFC + an advertised XPushList share service automatically imports only the camera's shared JPEG set.
It first uses the DID current-content item when available, otherwise enumerates PushRoot and selected
subfolders; it never falls back to PhotoRoot. The default is the largest available JPEG, using the
existing resource selector, queue, safe file GET retries, PhotoStore/MediaStore saving and dedup.
No extra import confirmation is shown: touching the camera with Companion open starts the requested
automatic import after Android Wi-Fi approval. This deliberately differs from Imaging Edge's import-size
confirmation and default 2 MP policy. RAW/MPO entries import their offered JPEG derivative, never an ARW
mislabelled as JPEG. Unsupported types, thumbnail-only items, empty/conflicting/cyclic/oversized selections
are reported before enqueueing. Up to 2,000 photos / 256 directories / 16 levels are supported; enumeration
is bounded by time as well as count. JPEG without XPushList remains manual, with an explanatory status.
Manual JPEG connections and QR-to-REMOTE are unchanged. API failure never reports pairing/session success.

The automatic batch holds a foreground-service lease through preparation and download, offers **取消导入**,
and uses the existing queue UI. The existing 2,000-record queue cap is checked before adding a batch; if history plus the selection
would exceed it, importing stops without overwriting those records. Historical recovered tasks stay pending for explicit retry; they are never
automatically included in a new share. The camera receives advisory X_GetPushRoot and item-count
X_TransferProgress notifications. Their timeout/failure is nonfatal, as in the inspected official app.
A started session receives one best-effort X_TransferEnd: 0 for success/partial failure, 2 for user
cancellation, 1 for total/preparation failure. Counts and error messages distinguish partial success.
The owned request is then released; no automatic transition into REMOTE occurs. A lost network or forced
OS/service cancellation aborts locally and cannot guarantee delivery of an end notification.

A separate generation-owned coordinator selects and observes the batch; every actual file import still
runs in the existing transfer queue. Detach captures/cancels/joins that coordinator with its own resources.
Queue-job capture and automatic detachment check the generation atomically under SessionIdentity.
A late completion cannot close or publish into a newer session. The capture and thumbnail implementations
are untouched; the automatic workspace shows progress instead of starting gallery thumbnail requests.

The 60-second Wi-Fi timeout, rejection and authentication/unreachable failures release the request.
Android's public callback does not reliably distinguish user refusal from wrong password or missing
hotspot; the error lists these possibilities rather than inventing a diagnosis. Home **取消连接** also
aborts an outstanding request or discovery. The existing reconnect button re-requests the live paired network using memory-only credentials.
Ending/failing the session clears those credentials; a later paired connection requires QR/NFC again.
End the current session before scanning/touching another camera. Late callbacks from a released request cannot connect or terminate a newer session.

An approval Activity can pause/stop MainActivity. The request belongs to the application controller,
not a Composable or Activity; it survives that transition and configuration recreation. Pending
credentials/permission continuation are memory-only and discarded on process death. The OS owns and
releases requests on process death. NFC foreground dispatch is enabled only while MainActivity is
resumed and disabled on pause. Duplicate NFC delivery during a pending/active session is ignored with
an explanation. No automatic retry is started when an app returns from the background. A platform
that drops the local network triggers session disconnection and requires a new user request.

## APK evidence (supplied Imaging Edge Mobile 7.8.6)

The APKS contains base.apk plus arm64-v8a, ms, zh, xhdpi and xxxhdpi splits. The base DEX files
contain the protocol logic. The supplied base.apk SHA-256 is
`c466640e136e2fe4bc1a50a3ab995d028c96f71921b5f973594a7a7114ec3501`. Analysis used DEX instruction inspection and Androguard DAD decompilation.
This implementation is independently written; no Sony classes, binaries, assets or decompiled source
are incorporated in the app.

### QR

- `com.sony.playmemories.mobile.qr.data.QrDataUtil.getVersion/getData/getSsid/removeEscapeCharacter`
  confirms version 1, S/P/C/M field markers, `DIRECT-` + S + `:` + C, and escaping `; , : \\`.
- `qr.QrReaderCallbackController.checkQrString` validates S length four and P length eight before
  constructing WifiQrData. Its WifiQrData stores version, prefix, friendly name and password;
  `getSsid()` delegates to QrDataUtil. The QR was also decoded directly from the supplied camera photograph and passed through the
  independently implemented Kotlin parser; its resulting SSID matches the observed ILCE-7RM2 network.
  The actual password/raw payload stayed out of test output and repository fixtures.
- M is recognized by QrDataUtil but is not used by that WifiQrData construction path. No evidence
  here establishes it as the AP BSSID or an application-level authentication token. Companion ignores
  optional M content and neither connects by that address nor treats it as camera identity. It imposes
  no unverified MAC syntax on a field that the inspected official Wi-Fi QR path does not consume.
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

### ILCE-7RM2 hardware confirmation and app dispatch (2026-11-06)

The user confirmed the first real-camera connection (19:03) used QR and the second (19:07) used NFC.
Both reached REMOTE, received live-view frames, and ended normally. The real phone was vivo V2502A,
SDK 37. This confirms the existing NFC parser/Wi-Fi/REMOTE path on that setup; unrelated photo capture
and download were not part of this pairing acceptance.

NFC Tools PRO screenshots from both the remote and sharing camera states establish NFC-F (JIS 6319-4),
Sony FeliCa RC-S926, NFC Forum Type 3, NDEF, 113/128 bytes and non-writable status. Both show a Sony MIME
record plus an External application record naming `com.sony.playmemories.mobile`. Visible credentials
and routing records match; the screenshots do not expose every byte, so binary equality or a mode
flag must not be inferred. No extra raw tag dump is needed to re-prove the working REMOTE path.

The application record is Android's AAR routing mechanism. A foreground Activity's
`enableForegroundDispatch` overrides AAR routing; Companion already registers for the Sony MIME while
resumed. There is no need to write the tag, add raw NFC-F commands, or register a package-name alias.
With Companion closed/backgrounded, the official package in the AAR remains the preferred target;
a normal MIME Intent filter or higher filter priority cannot reliably override that choice. Open
Companion before touching the camera. The non-writable camera tag is not modified, and Companion does
not masquerade as Sony's package. Foreground dispatch, Sony record parsing and session mode selection
are independent of that AAR target.

The camera's shooting/playback state determines its one-touch function. Sony documents starting the
registered camera application from shooting and sharing selected images from playback. Companion does
not invent an application-start handshake. On the phone, NFC connects the provided Wi-Fi and identifies
the currently advertised REMOTE or JPEG service. Source, Wi-Fi availability and selected mode are logged
without passwords or complete tag payloads. The Xiaomi 23127PN0CC / SDK 35 log exported at 19:48 confirms both NFC-to-REMOTE and NFC-to-JPEG,
one PushRoot item, its successful 2,490,368-byte JPEG download and normal X_TransferEnd / network
release. The original vivo REMOTE acceptance is retained. This does not diagnose the separate
main-phone NFC reading failure. See [the complete official one-touch sharing trace](IMAGING_EDGE_ONE_TOUCH_SHARING.md)
for its import confirmation, single-current-item versus PushRoot paths, size policy, progress,
completion/cancellation/error codes and disconnection. Automatic importing was added afterwards in
0.1.16-preview; those earlier hardware logs validate the original connection/manual download path,
not the newly added automatic-import coordinator.

References:
- https://developer.android.com/develop/connectivity/nfc/nfc (Android application records)
- https://developer.android.com/develop/connectivity/nfc/advanced-nfc (foreground dispatch)
- https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087155.html
- https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087158.html
- https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087160.html

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
NDEF adapter errors/AAR coexistence, active-service mode selection, NFC-to-JPEG/REMOTE integration,
DID profile/resource/filename mapping, RAW/MPO JPEG filtering, selected multi-file/subfolder traversal,
empty/unsupported/missing PushRoot rejection, end codes/advisory notifications, actual automatic
MediaStore import/dedup, keeping historical tasks pending, partial/total failure, cancellation from UI
and a background notification, network loss and superseded coordinators, and presence of manual/QR/NFC UI entries. These tests simulate callbacks and local
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
2. For remote, leave the camera in shooting mode with the one-touch remote application assigned.
   For sharing, display/select the image in playback/Send to Smartphone. Touch the phone's NFC antenna
   to the camera N mark for about two seconds. Android should deliver
   the Sony NDEF record; grant any requested Wi-Fi permission and accept the system confirmation.
3. In sharing, verify the automatic-import status, a saved JPEG in DCIM/AlphaCompanion and the final
   import count followed by disconnection, without pressing a Download button. Repeat the same item
   to verify valid existing-photo dedup. For a multi-image selection made on the camera, check that
   the whole selected set is imported. Cancel a longer batch; completed photos should remain and
   the pending file must not appear as a completed photo. These new automatic behaviors await real
   camera testing; the old REMOTE connection result remains established.
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

