# FilzaDrop

FilzaDrop is a Java 21 + JavaFX local-network file transfer prototype for Windows. It sends one file directly between two running instances without cloud storage or accounts.

## Requirements
- Windows 10/11
- JDK 21
- Maven 3.9+

## Run
Open this folder in VS Code, open Terminal in the folder containing `pom.xml`, and run:

```powershell
java -version
mvn -version
mvn clean javafx:run
```

## Test on two Windows PCs
1. Connect both PCs to the same trusted private Wi-Fi/LAN. Guest networks may block device-to-device traffic.
2. Run `mvn javafx:run` on both PCs.
3. On the receiving PC, click **Start receiving**. Note the displayed IPv4 address and one-time 8-digit pairing code.
4. If Windows Firewall prompts, allow Java on **Private networks only**. Do not enable public-network access or configure router port forwarding.
5. On the sending PC, choose a file, enter the receiver's local IP and code, and click **Send securely**.
6. The received file is saved to `%USERPROFILE%\Downloads\FilzaDrop`.
7. Verify both copies with `Get-FileHash "C:\path\to\file" -Algorithm SHA256` in PowerShell.

## Phase 1 features
- Dark purple JavaFX interface branded **FilzaDrop by Filza**
- File picker and drag-and-drop
- One-shot receiver and 8-digit pairing code
- AES-256-GCM authenticated encryption, key derived with PBKDF2-HMAC-SHA-256
- Random per-transfer salt and nonce
- Streaming I/O; files are not loaded wholly into memory
- Temporary receive file; final filename is published only after authentication succeeds
- Filename sanitization, collision-safe names, 10 GiB limit

## Limitations and security notes
- This is an early prototype, not an independently audited security product.
- Manual IP entry is used; automatic discovery is planned for a later phase.
- A receiver accepts one connection/file per session. Restart receiving for another transfer.
- The one-time code is a shared secret. Only share it with the intended sender.
- Both devices must be able to reach one another on the LAN. Wi-Fi client isolation, firewall settings, or VPN software can block it.
- No router port forwarding is required. Do not expose this prototype to the public internet.

## Structure
```text
FilzaDrop/
├── pom.xml
├── README.md
├── LICENSE
└── src/main/
    ├── java/dev/filza/filzadrop/FilzaDropApp.java
    ├── java/dev/filza/filzadrop/TransferService.java
    └── resources/styles.css
```
