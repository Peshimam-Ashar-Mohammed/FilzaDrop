# FilzaDrop

**Send files without the cloud.**

FilzaDrop is a zero-cloud, peer-to-peer file-sharing application for **Windows Desktop** (Java 21 + JavaFX) and **Android** (Kotlin + Jetpack Compose). Files stream directly between devices into `Downloads/FilzaDrop` with no accounts, no cloud buckets, and no external backend storage.

---

## Transfer Modes & Verification Status

| Transfer Path | Transport & Protocol | Encryption & Integrity | Multi-File Support | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Windows ↔ Windows (LAN / Local)** | TCP (`45821`) + UDP Discovery (`45822`), `FD_MULTI_V2` | AES-256-GCM (PBKDF2-HMAC-SHA256, 310,000 iterations) + per-file SHA-256 | Yes | **Verified** (Automated JUnit 5 end-to-end tests) |
| **Phone Browser ↔ Windows PC (LAN / Hotspot)** | Local HTTP Bridge (`45824`) + QR Pairing | Local HTTP with expiring single-use 6-digit code, per-IP rate limiting, `.part` temp files | Yes (`.zip` bundle on multi-file download) | **Verified** (Automated JUnit 5 security & transfer tests) |
| **Native Android ↔ Windows / Android (Same Wi-Fi)** | TCP (`45821`) + UDP Discovery (`45822`), `FD_MULTI_V2` | AES-256-GCM (PBKDF2-HMAC-SHA256, 310,000 iterations) + per-file SHA-256 | Yes | **APK & AAB Built & Verified**; physical multi-device test pending |
| **Cross-Network Relay (`RelayServer`)** | TCP Relay (`45823`) forwarding AES-256-GCM stream | End-to-end AES-256-GCM + SHA-256 between peers | Yes | **Local relay verified**; requires user-hosted public VPS for internet use |

---

## Security Model & Known Limitations

1. **Desktop & Native Android TCP Protocol (`Port 45821`)**:
   - Uses a 6-digit pairing code generated via `SecureRandom` together with a random 16-byte salt and 12-byte nonce per transfer to derive a 256-bit AES-GCM key (`PBKDF2WithHmacSHA256`, 310,000 iterations).
   - Every file in a single or multi-file batch (`FD_MULTI_V2`) is written to a temporary `.part` file inside `Downloads/FilzaDrop`, verified against an in-stream 32-byte SHA-256 digest and the final 128-bit GCM authentication tag, and atomically moved into place. Incomplete or tampered transfers are deleted automatically.
   - Note: A 6-digit code has $10^6$ combinations and is designed for short-lived, interactive local pairing rather than unauthenticated exposure to the public internet.

2. **Mobile Browser Bridge (`Port 45824`)**:
   - Designed for zero-install convenience when scanning the desktop QR code from a phone camera on a trusted local Wi-Fi network or mobile hotspot.
   - **Important Disclosure**: Because mobile browsers reject self-signed TLS certificates on raw LAN IP addresses with security warnings, the local browser bridge operates over **plain HTTP**. While pairing codes are stripped from browser URL history via `history.replaceState` and sent via the `X-FilzaDrop-Code` header, traffic on the local Wi-Fi segment is not TLS-encrypted. For end-to-end AES-256-GCM encryption on Android, use the native Android app rather than the browser bridge.
   - **Hardened Controls**:
     - Uploads are strictly rejected when no active receive session exists.
     - Upload and download sessions are completely isolated (an upload code cannot download files, and a download code cannot upload files).
     - Sessions expire after 10 minutes, enforce download count limits under concurrent requests, and lock out brute-force guessing per IP address (`5` failed upload guesses or `10` failed download guesses).
     - Incoming uploads stream to `.part` temporary files with strict byte-size enforcement (`10 GiB` default max) and filename sanitization against path traversal.

3. **Local vs. Cross-Network Transfers**:
   - Out of the box, FilzaDrop operates on the **same local network** (same Wi-Fi router or a phone's Mobile Hotspot).
   - A `RelayServer` running on `127.0.0.1:45823` only bridges processes on the same machine. Cross-network transfers over the internet require running `dev.filza.filzadrop.RelayServer` on a reachable host/VPS and configuring its `host:port` via the **Relay** button. Do not expose `MobileWebBridge` or an unmonitored relay directly to the public internet.

---

## Build, Test & Run (Windows Desktop)

### Prerequisites
- JDK 21
- Maven 3.9+

### 1. Run Automated Security & Transfer Tests
```bat
mvn clean test
```
Current test suite (`SecurityAndTransferTest`) runs **13 automated tests** verifying:
- Rejection of uploads when no receive session is active, when codes are invalid, or when sessions expire.
- Brute-force lockout on repeated invalid upload or download code attempts.
- Thread-safe download count limits under concurrent HTTP requests.
- Path traversal sanitization (`../../` stripped and contained within receive directory).
- Oversized upload rejection (`413`) and automatic `.part` file cleanup on interrupted transfers.
- End-to-end encrypted multi-file desktop transfers with SHA-256 verification.

### 2. Build & Run from Source
```bat
mvn clean package
java -jar target/filzadrop-1.0.0.jar
```
Or run `run.bat` during development.

### 3. Package Self-Contained Windows App Image & Release ZIP
```bat
mvn clean package
mkdir target\release-input
copy target\filzadrop-1.0.0.jar target\release-input\
jpackage --type app-image --name FilzaDrop --app-version 1.0.0 --input target\release-input --main-jar filzadrop-1.0.0.jar --main-class dev.filza.filzadrop.Launcher --dest dist
```
This creates `dist\FilzaDrop\` containing `FilzaDrop.exe`, `app\`, and a bundled Java `runtime\`. Always distribute the complete `FilzaDrop` directory (e.g., `FilzaDrop-Windows-1.0.0.zip`), never `FilzaDrop.exe` alone.

### Microsoft Store (MSIX) Preparation Notes
- `jpackage --type app-image` produces a portable Win32 folder, **not** an `.msix` package.
- To generate a Microsoft Store `.msix` using free official tools:
  1. Install the **Windows 10/11 SDK** (which provides `makeappx.exe` and `signtool.exe`) or the free **MSIX Packaging Tool** from the Microsoft Store.
  2. Reserve your app name in Microsoft Partner Center to obtain your assigned `Identity Name`, `Publisher` (`CN=...`), and `PublisherDisplayName`.
  3. Place an `AppxManifest.xml` and required PNG visual assets (`Square44x44Logo.png`, `Square150x150Logo.png`, `StoreLogo.png`) alongside the `jpackage` app-image and run `makeappx pack /d <input-folder> /p FilzaDrop.msix`. (Partner Center automatically signs uploaded packages for Store distribution at ₹0 cost.)

---

## Build & Package (Android App & Google Play AAB)

The authoritative Android project is located in [`packages/android/`](packages/android).

### Prerequisites
- JDK 17 or 21
- Android SDK Platform 35 (`android-35`)

### Build Debug APK & Release Android App Bundle (`.aab`)
```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
gradle -p packages\android assembleDebug bundleRelease
```
Outputs:
- Debug APK: `packages/android/app/build/outputs/apk/debug/app-debug.apk`
- Google Play Release Bundle: `packages/android/app/build/outputs/bundle/release/app-release.aab`

### Signing for Google Play Release
No signing keys are committed to the repository. To sign the release `.aab` with your upload keystore, set the following environment variables before running `bundleRelease`:
```powershell
$env:FILZADROP_KEYSTORE_PATH = "C:\path\to\upload-keystore.jks"
$env:FILZADROP_KEYSTORE_PASSWORD = "your-keystore-password"
$env:FILZADROP_KEY_ALIAS = "upload"
$env:FILZADROP_KEY_PASSWORD = "your-key-password"
gradle -p packages\android bundleRelease
```
*Note on Google Play Personal Developer Accounts*: Google Play requires personal developer accounts created after November 2023 to run a closed test with at least 12–20 opted-in testers for 14 continuous days before applying for production access.

---

## Network Ports & Firewall Requirements

| Port | Protocol | Purpose |
| :--- | :--- | :--- |
| `45821` | TCP | Direct encrypted file transfer (`TransferService` & `AndroidProtocol`) |
| `45822` | UDP | Local-network peer discovery & pairing-code resolution (`LanDiscoveryService`) |
| `45823` | TCP | Optional Relay Server port (`RelayServer`) |
| `45824` | TCP (HTTP) | Local Wi-Fi Mobile Browser Bridge (`MobileWebBridge`) |

If transfers fail on Windows, ensure **Windows Defender Firewall** permits inbound connections on TCP ports `45821` and `45824` and UDP port `45822` on **Private** networks. Received files are saved to `C:\Users\<Username>\Downloads\FilzaDrop` on Windows and `Downloads/FilzaDrop` on Android.

---

## Roadmap

- [x] Minimalist dark-purple JavaFX desktop UI with smooth scrolling and custom result dialogs
- [x] Multi-file selection and drag-and-drop support
- [x] Encrypted multi-file TCP protocol (`FD_MULTI_V2`) with AES-256-GCM + SHA-256 verification
- [x] Hardened local mobile web bridge with per-IP rate limiting, session expiry, and `.part` atomic file commits
- [x] Native Android app (`packages/android`) with multi-file selection, UDP discovery, and `FD_MULTI_V2` support
- [x] Automated JUnit 5 security and transfer regression suite (13 tests passing)
- [ ] Physical two-device Android-to-Android and Windows-to-Windows verification across separate hardware
- [ ] Explicit receiver accept/decline prompt before incoming stream starts
- [ ] Public TLS-secured relay deployment for zero-configuration internet transfers

---

## License

Released under the [MIT License](LICENSE).