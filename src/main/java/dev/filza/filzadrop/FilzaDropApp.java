package dev.filza.filzadrop;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class FilzaDropApp extends Application {
    private static final String EMPTY_CODE = "— — — — — —";

    private Stage stage;
    private Label status;
    private Label pairingCode;
    private ImageView receiveQrView;
    private VBox qrContainer;
    private Label fileLabel;
    private Label fileMetaLabel;
    private Label progressText;
    private ProgressBar progress;
    private TextField codeField;
    private Button receiveButton;
    private Button cancelReceiveButton;
    private Button sendButton;
    private Button cancelSendButton;
    private FlowPane peerChips;
    private List<Path> chosenFiles = new ArrayList<>();

    private ServerSocket server;
    private LanDiscoveryService discoveryService;
    private RelayServer localRelayServer;
    private MobileWebBridge mobileWebBridge;
    private TransferService.TransferHandle activeHandle;
    private final Map<String, LanDiscoveryService.DiscoveredPeer> discoveredPeers = new ConcurrentHashMap<>();
    private boolean busy;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        startBackgroundServices();

        VBox content = createContentLayout();
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("main-scroll");

        Scene scene = new Scene(scrollPane, 840, 570);
        scene.getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm()
        );

        stage.setTitle("FilzaDrop");
        stage.setMinWidth(620);
        stage.setMinHeight(480);
        stage.setScene(scene);
        stage.show();
    }

    private void startBackgroundServices() {
        try {
            localRelayServer = new RelayServer(RelayServer.DEFAULT_RELAY_PORT);
            localRelayServer.start();
        } catch (IOException ignored) {
        }

        mobileWebBridge = new MobileWebBridge(receivedPaths -> Platform.runLater(() -> {
            // Cancel the parallel TCP listener first so closing ServerSocket does not trigger a false error dialog
            if (activeHandle != null) {
                activeHandle.cancel();
            }
            finishTransfer();
            progress.setProgress(1);
            String summary = formatReceivedFilesSummary(receivedPaths);
            progressText.setText("Saved " + receivedPaths.size() + " file(s)");
            setStatus("Received from phone into Downloads/FilzaDrop");
            showTransferResultDialog(
                    true,
                    "Transfer Complete",
                    "Received from phone:\n" + summary + "\n\nSaved in: " + TransferService.receiveDir(),
                    TransferService.receiveDir(),
                    null
            );
        }));
        try {
            mobileWebBridge.start();
        } catch (IOException ignored) {
        }

        String deviceName = System.getProperty("user.name", "PC");
        discoveryService = new LanDiscoveryService(
                deviceName,
                "Windows",
                peer -> Platform.runLater(() -> {
                    discoveredPeers.put(peer.ip() + ":" + peer.port(), peer);
                    refreshPeerChips();
                })
        );
        discoveryService.start();
    }

    private VBox createContentLayout() {
        VBox root = new VBox(16,
                createTopBar(),
                createWorkspaceColumns(),
                createPeersRow(),
                createBottomBar()
        );
        root.setPadding(new Insets(22, 26, 22, 26));
        root.getStyleClass().add("content-root");
        return root;
    }

    private HBox createTopBar() {
        Label brand = styledLabel("FilzaDrop", "brand");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button privacyBtn = new Button("Direct · No Cloud");
        privacyBtn.getStyleClass().add("ghost-button");
        privacyBtn.setOnAction(e -> showPrivacyDialog());

        Button helpBtn = new Button("How It Works");
        helpBtn.getStyleClass().add("ghost-button");
        helpBtn.setOnAction(e -> showHelpDialog());

        Button qrModalBtn = new Button("Phone QR");
        qrModalBtn.getStyleClass().add("ghost-button");
        qrModalBtn.setOnAction(e -> showPhoneQrDialog());

        Button downloadsBtn = new Button("Downloads Folder");
        downloadsBtn.getStyleClass().add("ghost-button");
        downloadsBtn.setOnAction(e -> openDownloadsFolder());

        Button relayBtn = new Button("Relay");
        relayBtn.getStyleClass().add("ghost-button");
        relayBtn.setOnAction(e -> showRelayDialog());

        HBox topBar = new HBox(8, brand, spacer, privacyBtn, helpBtn, qrModalBtn, downloadsBtn, relayBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        return topBar;
    }

    private HBox createWorkspaceColumns() {
        VBox sendPane = createSendPane();
        VBox receivePane = createReceivePane();

        HBox columns = new HBox(16, sendPane, receivePane);
        HBox.setHgrow(sendPane, Priority.ALWAYS);
        HBox.setHgrow(receivePane, Priority.ALWAYS);
        VBox.setVgrow(columns, Priority.ALWAYS);
        return columns;
    }

    private VBox createSendPane() {
        Label header = styledLabel("Send", "pane-title");
        Label sub = styledLabel("Select one or multiple files and enter the 6-digit code", "pane-sub");

        fileLabel = styledLabel("Drop files or click to browse", "drop-title");
        fileMetaLabel = styledLabel("Multiple files supported · Up to 10 GB · AES-256-GCM", "drop-meta");

        VBox dropZone = new VBox(4, fileLabel, fileMetaLabel);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setMinHeight(104);
        dropZone.setMaxWidth(Double.MAX_VALUE);
        dropZone.getStyleClass().add("drop-zone");
        VBox.setVgrow(dropZone, Priority.ALWAYS);
        configureFileDropZone(dropZone);

        codeField = new TextField();
        codeField.setPromptText("6-digit code");
        codeField.getStyleClass().add("code-input");
        codeField.setAlignment(Pos.CENTER);
        codeField.textProperty().addListener((obs, oldV, newV) -> keepPairingCodeDigits(newV));

        sendButton = new Button("Send Files");
        sendButton.getStyleClass().addAll("primary-btn", "wide");
        sendButton.setOnAction(e -> sendFile());

        cancelSendButton = new Button("Cancel");
        cancelSendButton.getStyleClass().addAll("cancel-btn", "wide");
        cancelSendButton.setOnAction(e -> cancelActiveTransfer());
        cancelSendButton.setVisible(false);
        cancelSendButton.setManaged(false);

        VBox pane = new VBox(12, header, sub, dropZone, codeField, sendButton, cancelSendButton);
        pane.getStyleClass().add("pane");
        pane.setPadding(new Insets(18));
        return pane;
    }

    private VBox createReceivePane() {
        Label header = styledLabel("Receive", "pane-title");
        Label sub = styledLabel("Share the 6-digit code or scan QR with your phone", "pane-sub");

        pairingCode = styledLabel(EMPTY_CODE, "code-box");
        pairingCode.setMaxWidth(Double.MAX_VALUE);
        pairingCode.setMinHeight(104);
        pairingCode.setAlignment(Pos.CENTER);
        HBox.setHgrow(pairingCode, Priority.ALWAYS);
        pairingCode.setOnMouseClicked(e -> {
            String code = pairingCode.getText().replaceAll("\\D", "");
            if (!code.isEmpty()) {
                copyToClipboard(code);
                setStatus("Copied code " + code);
            }
        });

        receiveQrView = new ImageView();
        receiveQrView.setFitWidth(96);
        receiveQrView.setFitHeight(96);
        receiveQrView.setPreserveRatio(true);

        qrContainer = new VBox(receiveQrView);
        qrContainer.setAlignment(Pos.CENTER);
        qrContainer.getStyleClass().add("qr-box");
        qrContainer.setVisible(false);
        qrContainer.setManaged(false);
        qrContainer.setOnMouseClicked(e -> showPhoneQrDialog());

        HBox codeAndQrRow = new HBox(10, pairingCode, qrContainer);
        codeAndQrRow.setAlignment(Pos.CENTER);
        VBox.setVgrow(codeAndQrRow, Priority.ALWAYS);

        Label saveHint = styledLabel("Saves to Downloads/FilzaDrop · Click code to copy", "pane-sub");

        receiveButton = new Button("Listen for Files");
        receiveButton.getStyleClass().addAll("primary-btn", "wide");
        receiveButton.setOnAction(e -> startReceiving());

        cancelReceiveButton = new Button("Cancel");
        cancelReceiveButton.getStyleClass().addAll("cancel-btn", "wide");
        cancelReceiveButton.setOnAction(e -> cancelActiveTransfer());
        cancelReceiveButton.setVisible(false);
        cancelReceiveButton.setManaged(false);

        VBox pane = new VBox(12, header, sub, codeAndQrRow, saveHint, receiveButton, cancelReceiveButton);
        pane.getStyleClass().add("pane");
        pane.setPadding(new Insets(18));
        return pane;
    }

    private HBox createPeersRow() {
        Label label = styledLabel("Devices:", "peers-label");
        peerChips = new FlowPane(8, 8);
        peerChips.setAlignment(Pos.CENTER_LEFT);
        peerChips.getChildren().add(styledLabel("Searching local network…", "pane-sub"));

        HBox row = new HBox(10, label, peerChips);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("peers-bar");
        row.setPadding(new Insets(10, 14, 10, 14));
        return row;
    }

    private VBox createBottomBar() {
        status = styledLabel("Ready", "status-text");
        progressText = styledLabel("", "pane-sub");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, status, spacer, progressText);
        row.setAlignment(Pos.CENTER_LEFT);

        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("progress");

        return new VBox(6, row, progress);
    }

    private void refreshPeerChips() {
        if (peerChips == null) return;
        peerChips.getChildren().clear();

        if (discoveredPeers.isEmpty()) {
            peerChips.getChildren().add(styledLabel("Searching local network…", "pane-sub"));
            return;
        }

        for (LanDiscoveryService.DiscoveredPeer peer : discoveredPeers.values()) {
            String text = peer.name() + " (" + peer.platform() + ")"
                    + (peer.activeCode().isEmpty() ? "" : " · " + peer.activeCode());
            Button chip = new Button(text);
            chip.getStyleClass().add("peer-chip");
            chip.setOnAction(e -> {
                if (!peer.activeCode().isEmpty()) {
                    codeField.setText(peer.activeCode());
                    setStatus("Selected code " + peer.activeCode() + " (" + peer.name() + ")");
                } else {
                    setStatus(peer.name() + " is online (not listening yet)");
                }
            });
            peerChips.getChildren().add(chip);
        }
    }

    private void configureFileDropZone(VBox dropZone) {
        dropZone.setOnMouseClicked(event -> {
            if (!busy) chooseFiles();
        });
        dropZone.setOnDragOver(event -> {
            if (!busy && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        dropZone.setOnDragDropped(event -> {
            boolean accepted = !busy && event.getDragboard().hasFiles();
            if (accepted) {
                List<Path> paths = event.getDragboard().getFiles().stream()
                        .map(File::toPath)
                        .filter(Files::isRegularFile)
                        .toList();
                if (!paths.isEmpty()) {
                    setFiles(paths);
                }
            }
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    private void startReceiving() {
        if (busy) return;

        try {
            server = new ServerSocket(TransferService.PORT);
            String code = TransferService.newCode();
            activeHandle = new TransferService.TransferHandle();

            pairingCode.setText(code.substring(0, 3) + " " + code.substring(3));
            if (discoveryService != null) {
                discoveryService.setActivePairingCode(code);
            }
            if (mobileWebBridge != null) {
                mobileWebBridge.setActiveReceiveCode(code);
                String qrUrl = mobileWebBridge.getMobileUrl(code);
                receiveQrView.setImage(QrGenerator.generate(qrUrl, 160));
                qrContainer.setVisible(true);
                qrContainer.setManaged(true);
            }

            setTransferMode(true, true);
            setStatus("Listening with code " + code);
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressText.setText("Waiting for sender");

            Task<List<Path>> task = new Task<>() {
                @Override
                protected List<Path> call() throws Exception {
                    return TransferService.receiveFilesAnyNetwork(
                            server,
                            code,
                            TransferService.receiveDir(),
                            activeHandle,
                            (got, total) -> Platform.runLater(() -> updateProgressUI(got, total))
                    );
                }
            };

            task.setOnSucceeded(event -> {
                List<Path> receivedFiles = task.getValue();
                finishTransfer();
                progress.setProgress(1);
                String summary = formatReceivedFilesSummary(receivedFiles);
                progressText.setText("Saved " + receivedFiles.size() + " file(s)");
                setStatus("Saved to " + TransferService.receiveDir());
                showTransferResultDialog(
                        true,
                        "Transfer Complete",
                        "Verified (AES-256-GCM + SHA-256) and saved:\n" + summary + "\n\nFolder: " + TransferService.receiveDir(),
                        TransferService.receiveDir(),
                        null
                );
            });

            task.setOnFailed(event -> {
                Throwable ex = task.getException();
                boolean wasCancelled = (activeHandle != null && activeHandle.isCancelled())
                        || ex instanceof TransferService.CancellationException;
                finishTransfer();
                progress.setProgress(0);
                progressText.setText("");
                if (wasCancelled) {
                    setStatus("Receive cancelled");
                } else {
                    setStatus("Receive failed");
                    showTransferResultDialog(
                            false,
                            "Transfer Failed",
                            message(ex) + "\n\nMake sure the sender entered the matching 6-digit pairing code.",
                            null,
                            this::startReceiving
                    );
                }
            });

            startTask(task, "filzadrop-receiver");
        } catch (IOException ex) {
            setStatus("Port busy: " + ex.getMessage());
            showTransferResultDialog(
                    false,
                    "Receiver Unavailable",
                    "Port " + TransferService.PORT + " is already in use.\n" + ex.getMessage(),
                    null,
                    null
            );
        }
    }

    private void sendFile() {
        if (busy) return;

        List<Path> validFiles = chosenFiles.stream().filter(Files::isRegularFile).toList();
        if (validFiles.isEmpty()) {
            chooseFiles();
            validFiles = chosenFiles.stream().filter(Files::isRegularFile).toList();
            if (validFiles.isEmpty()) return;
        }

        String code = codeField.getText().replaceAll("\\D", "");
        if (code.length() != 6) {
            setStatus("Enter a 6-digit pairing code first");
            codeField.requestFocus();
            return;
        }

        final List<Path> filesToSend = validFiles;
        activeHandle = new TransferService.TransferHandle();
        if (mobileWebBridge != null) {
            mobileWebBridge.registerOutgoingFiles(code, filesToSend);
        }

        setTransferMode(true, false);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressText.setText("Connecting");
        setStatus("Sending " + filesToSend.size() + " file(s)…");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                TransferService.sendFilesByCode(
                        filesToSend,
                        code,
                        discoveryService,
                        activeHandle,
                        (sent, total) -> Platform.runLater(() -> updateProgressUI(sent, total))
                );
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            finishTransfer();
            progress.setProgress(1);
            progressText.setText("100%");
            String summary = formatReceivedFilesSummary(filesToSend);
            setStatus("Sent " + filesToSend.size() + " file(s)");
            showTransferResultDialog(
                    true,
                    "Transfer Complete",
                    "Delivered " + filesToSend.size() + " encrypted file(s):\n" + summary,
                    null,
                    null
            );
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            boolean wasCancelled = (activeHandle != null && activeHandle.isCancelled())
                    || ex instanceof TransferService.CancellationException;
            finishTransfer();
            progress.setProgress(0);
            progressText.setText("");
            if (wasCancelled) {
                setStatus("Send cancelled");
            } else {
                setStatus("Send failed");
                showTransferResultDialog(
                        false,
                        "Transfer Failed",
                        message(ex) + "\n\nEnsure the receiving device is listening with code " + code
                                + ", or click 'Phone QR' if sending to a phone browser.",
                        null,
                        this::sendFile
                );
            }
        });

        startTask(task, "filzadrop-sender");
    }

    private String formatReceivedFilesSummary(List<Path> files) {
        if (files == null || files.isEmpty()) return "";
        if (files.size() <= 4) {
            return files.stream()
                    .map(p -> "• " + p.getFileName())
                    .collect(Collectors.joining("\n"));
        }
        return files.subList(0, 3).stream()
                .map(p -> "• " + p.getFileName())
                .collect(Collectors.joining("\n"))
                + "\n• +" + (files.size() - 3) + " more file(s)";
    }

    private void openDownloadsFolder() {
        try {
            Path dir = TransferService.receiveDir().toAbsolutePath();
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
            }
            setStatus("Opened " + dir);
        } catch (Exception ex) {
            setStatus("Downloads folder: " + TransferService.receiveDir());
        }
    }

    private void showHelpDialog() {
        Stage dialog = new Stage(StageStyle.TRANSPARENT);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        Label badge = styledLabel("GUIDE", "dialog-badge-ok");
        Label heading = styledLabel("How to Transfer Between Phone & Laptop", "dialog-title");
        Label body = styledLabel(
                "1. Connect Both Devices\n" +
                "   Connect your phone and laptop to the same Wi-Fi network (or turn on your phone's Mobile Hotspot and connect your laptop to it).\n\n" +
                "2. Phone → Laptop (Receive on PC)\n" +
                "   • Click 'Listen for Files' on your laptop.\n" +
                "   • Scan the QR code shown on your laptop with your phone's camera.\n" +
                "   • Pick one or multiple files on your phone and tap 'Send Files'.\n\n" +
                "3. Laptop → Phone (Send to Phone)\n" +
                "   • Select one or multiple files in the 'Send' box on your laptop.\n" +
                "   • Click 'Phone QR' at the top and scan the QR code with your phone.\n" +
                "   • Tap 'Receive Files' on your phone to download them directly.\n\n" +
                "4. Laptop ↔ Laptop\n" +
                "   • Click 'Listen for Files' on the receiving PC to get a 6-digit code.\n" +
                "   • Select files on the sending PC, enter the 6-digit code, and click 'Send Files'.",
                "dialog-body"
        );
        body.setWrapText(true);

        Button closeBtn = new Button("Got It");
        closeBtn.getStyleClass().add("primary-btn");
        closeBtn.setOnAction(e -> dialog.close());

        HBox actions = new HBox(closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(14, badge, heading, body, actions);
        card.setPadding(new Insets(22));
        card.setPrefWidth(460);
        card.getStyleClass().add("dialog-card-ok");

        Scene scene = new Scene(card);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showPrivacyDialog() {
        Stage dialog = new Stage(StageStyle.TRANSPARENT);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        Label badge = styledLabel("ZERO-CLOUD ARCHITECTURE", "dialog-badge-ok");
        Label heading = styledLabel("Direct Peer-to-Peer · Nothing Stored on Any Backend", "dialog-title");
        Label body = styledLabel(
                "• No Cloud Storage or Servers:\n" +
                "  Your files are never uploaded to any third-party server, database, or cloud bucket. Bytes stream directly from the sender's device into your local Downloads/FilzaDrop folder.\n\n" +
                "• No Accounts or Tracking:\n" +
                "  FilzaDrop requires no sign-up, collects no telemetry, and stores zero file metadata externally.\n\n" +
                "• End-to-End Authenticated Encryption:\n" +
                "  Desktop-to-desktop and native app transfers are encrypted using AES-256-GCM (PBKDF2-HMAC-SHA256 with 310,000 iterations) and verified with SHA-256 integrity hashes before saving.\n\n" +
                "• One-Time Ephemeral Pairing Codes:\n" +
                "  Every 6-digit pairing code is generated with SecureRandom, expires automatically, locks out after repeated failed guesses, and is destroyed immediately after the transfer completes.",
                "dialog-body"
        );
        body.setWrapText(true);

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("primary-btn");
        closeBtn.setOnAction(e -> dialog.close());

        HBox actions = new HBox(closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(14, badge, heading, body, actions);
        card.setPadding(new Insets(22));
        card.setPrefWidth(460);
        card.getStyleClass().add("dialog-card-ok");

        Scene scene = new Scene(card);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showTransferResultDialog(
            boolean success,
            String title,
            String detail,
            Path folderToOpen,
            Runnable onRetry
    ) {
        Stage dialog = new Stage(StageStyle.TRANSPARENT);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        Label badge = styledLabel(success ? "COMPLETED" : "ERROR", success ? "dialog-badge-ok" : "dialog-badge-err");
        Label heading = styledLabel(title, "dialog-title");
        Label body = styledLabel(detail, "dialog-body");
        body.setWrapText(true);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (folderToOpen != null) {
            Button openFolderBtn = new Button("Open Downloads Folder");
            openFolderBtn.getStyleClass().add("ghost-button");
            openFolderBtn.setOnAction(e -> {
                openDownloadsFolder();
                dialog.close();
            });
            actions.getChildren().add(openFolderBtn);
        }

        if (!success && onRetry != null) {
            Button retryBtn = new Button("Try Again");
            retryBtn.getStyleClass().add("primary-btn");
            retryBtn.setOnAction(e -> {
                dialog.close();
                onRetry.run();
            });
            actions.getChildren().add(retryBtn);
        }

        Button closeBtn = new Button(success ? "Done" : "Dismiss");
        closeBtn.getStyleClass().add(success ? "primary-btn" : "ghost-button");
        closeBtn.setOnAction(e -> dialog.close());
        actions.getChildren().add(closeBtn);

        VBox card = new VBox(14, badge, heading, body, actions);
        card.setPadding(new Insets(22));
        card.setPrefWidth(400);
        card.getStyleClass().add(success ? "dialog-card-ok" : "dialog-card-err");

        Scene scene = new Scene(card);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showPhoneQrDialog() {
        String activeCode = pairingCode.getText().replaceAll("\\D", "");
        if (activeCode.isEmpty() && !codeField.getText().isBlank()) {
            activeCode = codeField.getText().replaceAll("\\D", "");
        }
        List<Path> validFiles = chosenFiles.stream().filter(Files::isRegularFile).toList();
        if (activeCode.isEmpty() && !validFiles.isEmpty()) {
            activeCode = TransferService.newCode();
            codeField.setText(activeCode);
        }
        if (!activeCode.isEmpty() && !validFiles.isEmpty() && mobileWebBridge != null) {
            mobileWebBridge.registerOutgoingFiles(activeCode, validFiles);
        }

        String url = mobileWebBridge != null
                ? mobileWebBridge.getMobileUrl(activeCode)
                : "http://" + TransferService.getLocalIpv4Hint() + ":" + MobileWebBridge.WEB_PORT;

        Stage dialog = new Stage(StageStyle.TRANSPARENT);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        Label heading = styledLabel("Scan with Phone Camera", "dialog-title");
        Label sub = styledLabel(
                activeCode.isEmpty()
                        ? "Click 'Listen for Files' or select files to send first, or scan to open portal:"
                        : "Paired with code " + activeCode + " · Scan to send or receive on your phone",
                "dialog-body"
        );
        sub.setWrapText(true);

        ImageView qr = new ImageView(QrGenerator.generate(url, 200));
        VBox qrWrap = new VBox(qr);
        qrWrap.setAlignment(Pos.CENTER);
        qrWrap.getStyleClass().add("qr-box");
        qrWrap.setPadding(new Insets(12));

        Label urlLabel = styledLabel(url, "pane-sub");

        Button copyBtn = new Button("Copy Link");
        copyBtn.getStyleClass().add("ghost-button");
        copyBtn.setOnAction(e -> {
            copyToClipboard(url);
            setStatus("Copied link: " + url);
        });

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("primary-btn");
        closeBtn.setOnAction(e -> dialog.close());

        HBox actions = new HBox(10, copyBtn, closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(14, heading, sub, qrWrap, urlLabel, actions);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(22));
        card.setPrefWidth(360);
        card.getStyleClass().add("dialog-card-ok");

        Scene scene = new Scene(card);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showRelayDialog() {
        Stage dialog = new Stage(StageStyle.TRANSPARENT);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        Label heading = styledLabel("Cross-Network Relay", "dialog-title");
        Label sub = styledLabel("Set relay server address (host:port) for transfers across different networks.", "dialog-body");
        sub.setWrapText(true);

        TextField input = new TextField(TransferService.getRelayEndpoint());
        input.getStyleClass().add("code-input");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("primary-btn");
        saveBtn.setOnAction(e -> {
            String trimmed = input.getText().trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0) {
                try {
                    String h = trimmed.substring(0, colon).trim();
                    int p = Integer.parseInt(trimmed.substring(colon + 1).trim());
                    TransferService.setRelayEndpoint(h, p);
                    setStatus("Relay set to " + TransferService.getRelayEndpoint());
                } catch (NumberFormatException ignored) {}
            }
            dialog.close();
        });

        HBox actions = new HBox(10, cancelBtn, saveBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(14, heading, sub, input, actions);
        card.setPadding(new Insets(22));
        card.setPrefWidth(360);
        card.getStyleClass().add("dialog-card-ok");

        Scene scene = new Scene(card);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void cancelActiveTransfer() {
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        closeServer();
        finishTransfer();
        progress.setProgress(0);
        progressText.setText("");
        setStatus("Cancelled");
    }

    private void updateProgressUI(long current, long total) {
        if (total > 0) {
            double pct = (double) current / total;
            progress.setProgress(pct);
            int percentInt = (int) Math.round(pct * 100);
            progressText.setText(percentInt + "% · " + TransferService.bytes(current) + " / " + TransferService.bytes(total));
        } else {
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressText.setText(TransferService.bytes(current));
        }
    }

    private void setTransferMode(boolean active, boolean isReceiving) {
        busy = active;
        receiveButton.setVisible(!active || !isReceiving);
        receiveButton.setManaged(!active || !isReceiving);
        receiveButton.setDisable(active);

        cancelReceiveButton.setVisible(active && isReceiving);
        cancelReceiveButton.setManaged(active && isReceiving);

        sendButton.setVisible(!active || isReceiving);
        sendButton.setManaged(!active || isReceiving);
        sendButton.setDisable(active);

        cancelSendButton.setVisible(active && !isReceiving);
        cancelSendButton.setManaged(active && !isReceiving);

        codeField.setDisable(active);
    }

    private void finishTransfer() {
        setTransferMode(false, false);
        closeServer();
        pairingCode.setText(EMPTY_CODE);
        if (qrContainer != null) {
            qrContainer.setVisible(false);
            qrContainer.setManaged(false);
        }
        if (discoveryService != null) {
            discoveryService.setActivePairingCode("");
        }
        if (mobileWebBridge != null) {
            mobileWebBridge.setActiveReceiveCode("");
        }
    }

    private void closeServer() {
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File(s) to Send");
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads)) {
            chooser.setInitialDirectory(downloads.toFile());
        }
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            setFiles(files.stream().map(File::toPath).toList());
        }
    }

    private void setFiles(List<Path> paths) {
        chosenFiles = new ArrayList<>(paths);
        try {
            long totalBytes = 0;
            for (Path p : chosenFiles) {
                totalBytes += Files.size(p);
            }
            if (chosenFiles.size() == 1) {
                fileLabel.setText(chosenFiles.get(0).getFileName().toString());
            } else {
                fileLabel.setText(chosenFiles.size() + " files selected (" + chosenFiles.get(0).getFileName() + " + " + (chosenFiles.size() - 1) + " more)");
            }
            fileMetaLabel.setText(TransferService.bytes(totalBytes) + " total · Click to change");
        } catch (IOException ex) {
            fileLabel.setText("Cannot read selected file(s)");
        }
    }

    private void keepPairingCodeDigits(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() > 6) {
            digits = digits.substring(0, 6);
        }
        if (!digits.equals(value)) {
            codeField.setText(digits);
        }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void startTask(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private String message(Throwable exception) {
        if (exception == null || exception.getMessage() == null) {
            return "Connection interrupted.";
        }
        return exception.getMessage().toLowerCase(Locale.ROOT).contains("cancelled")
                ? "Cancelled"
                : exception.getMessage();
    }

    @Override
    public void stop() {
        if (activeHandle != null) activeHandle.cancel();
        closeServer();
        if (discoveryService != null) discoveryService.close();
        if (mobileWebBridge != null) mobileWebBridge.close();
        if (localRelayServer != null) localRelayServer.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
