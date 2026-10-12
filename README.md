# FilzaDrop

**Send files without the cloud.**

FilzaDrop is a file-sharing application built with Java, designed to make transferring files between devices simple and convenient. The project focuses on a clean desktop experience, local-network file sharing, and exploring cross-platform device connectivity.

## ✨ Features

- **Desktop application:** A JavaFX-based Windows interface.
- **Local file sharing:** Transfer files between compatible devices on a network.
- **Modern UI:** A minimalist interface with a deep-purple theme.
- **Transfer management:** Designed to support transfer progress and cancellation.
- **Device discovery:** Discover compatible devices on the local network.
- **Mobile connectivity:** Includes Android application source code and a browser-based mobile bridge.
- **Java runtime bundled:** The packaged Windows build includes its own Java runtime.
- **Open source:** Explore the code, report issues, and contribute improvements.

> Feature availability depends on the current implementation and configuration. Test mobile connectivity, transfer cancellation, and cross-network transfers before relying on them.

## 🖥️ Download for Windows

Visit the [FilzaDrop Releases page](https://github.com/Peshimam-Ashar-Mohammed/FilzaDrop/releases).

If a Windows ZIP release is available:

1. Download `FilzaDrop-Windows-1.0.0.zip`.
2. Extract the ZIP file.
3. Open the extracted `FilzaDrop` folder.
4. Run `FilzaDrop.exe`.
5. Allow network access through Windows Firewall when appropriate for your network.

**Important:** Keep the `app` and `runtime` folders alongside `FilzaDrop.exe`. Do not move the executable out of its application folder.

If no release is published yet, see the build instructions below.

## 🛠️ Built With

- Java 21
- JavaFX
- Maven
- Kotlin and Jetpack Compose for the Android project
- TCP/IP networking
- Git and GitHub

## 🚀 Run from Source

### Prerequisites

- JDK 21
- Maven
- Git

### 1. Clone the repository

```bash
git clone https://github.com/Peshimam-Ashar-Mohammed/FilzaDrop.git
cd FilzaDrop
```

### 2. Build the desktop application

```bash
mvn clean package
```

### 3. Run the application

```bash
java -jar target/filzadrop-1.0.0.jar
```

## 📦 Package for Windows

On Windows, after successfully building the project, run:

```bat
mkdir target\release-input
copy target\filzadrop-1.0.0.jar target\release-input\
jpackage --type app-image --name FilzaDrop --app-version 1.0.0 --input target\release-input --main-jar filzadrop-1.0.0.jar --main-class dev.filza.filzadrop.Launcher --dest dist
```

The packaged application will be generated in:

```text
dist/
└── FilzaDrop/
    ├── FilzaDrop.exe
    ├── app/
    └── runtime/
```

To launch it:

```bat
dist\FilzaDrop\FilzaDrop.exe
```

To distribute it, ZIP the **contents of the complete `dist\FilzaDrop` directory**, not just the executable.

## 📱 Android

The repository contains Android project source code.

To build an Android APK, open the appropriate Android project directory in Android Studio, allow Gradle to synchronize, and build the APK using **Build → Build APK(s)**.

The Android build and device-to-device compatibility should be tested before publishing an APK.

## 🌐 Network Requirements

- For local-network transfers, connect the participating devices to a network that permits communication between them.
- Windows Firewall or router settings may block incoming connections.
- Cross-network transfers require a reachable relay or another supported connectivity configuration; a relay running only on `localhost` cannot connect remote devices over the internet.
- Use only trusted networks and transfer files to devices you recognize.

## 🔐 Security

Security is an ongoing part of FilzaDrop's development.

Before using FilzaDrop for sensitive files, verify the current implementation of encryption, device pairing, file validation, and mobile uploads. Do not expose an unreviewed relay or mobile upload service directly to the public internet.

## 🗺️ Roadmap

- [ ] Reliable Windows-to-Windows transfers
- [ ] Tested Android integration
- [ ] Improved device discovery and duplicate filtering
- [ ] Clear transfer progress and cancellation
- [ ] Secure pairing and mobile upload validation
- [ ] Simplified installation and release packaging
- [ ] Improved cross-network connectivity
- [ ] Automated builds and release testing

## 🤝 Contributing

Contributions, bug reports, and feature suggestions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make and test your changes.
4. Submit a pull request describing the changes.

For bugs, include your operating system, Java version, steps to reproduce the issue, and relevant error logs. Do not include passwords, private keys, or sensitive files.

## 📄 License

Check the repository's license file for the applicable license and usage terms.

## 👨‍💻 Author

**Ashar**

- GitHub: [Peshimam-Ashar-Mohammed](https://github.com/Peshimam-Ashar-Mohammed)
- Project: [FilzaDrop](https://github.com/Peshimam-Ashar-Mohammed/FilzaDrop)

---

*FilzaDrop — Send files without the cloud.*