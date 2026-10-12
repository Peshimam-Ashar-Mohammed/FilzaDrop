package dev.filza.filzadrop;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public class FilzaDropApp extends Application {
    private static final int PORT = 45821;
    private static final String EMPTY_PAIRING_CODE = "────────";

    private Stage stage;
    private Label status;
    private Label pairingCode;
    private Label fileLabel;
    private Label receivePath;
    private Label progressText;
    private ProgressBar progress;
    private TextField ipField;
    private PasswordField codeField;
    private Button receiveButton;
    private Button sendButton;
    private Button sendSelectedButton;
    private Button scanLanButton;
    private Path chosenFile;
    private ServerSocket server;
    private LanDiscoveryService discoveryService;
    private ListView<String> nearbyDevices;
    private TextField deviceSearchField;
    private final java.util.Map<String, String> discoveredDevices =
            new java.util.concurrent.ConcurrentHashMap<>();
    private boolean busy;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        VBox root = createRootLayout();
        Scene scene = createScene(root);

        stage.setTitle("FilzaDrop");
        stage.setMinWidth(850);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();

        startDiscovery();
        playEntranceAnimation(root);
    }

    private VBox createRootLayout() {
        VBox root = new VBox(
                18,
                createTopBar(),
                createHero(),
                createActionCards(),
                createNearbyPanel(),
                createTransferPanel(),
                createFooter()
        );
        root.setPadding(new Insets(24, 28, 18, 28));
        root.getStyleClass().add("root");
        return root;
    }

    private HBox createTopBar() {
        Label mark = styledLabel("F", "logo");
        Label name = styledLabel("FilzaDrop", "brand");
        Label subtitle = styledLabel("PRIVATE FILE TRANSFER", "eyebrow");
        Label networkPill = styledLabel("●  LOCAL NETWORK", "pill");

        VBox brand = new VBox(2, name, subtitle);
        HBox topBar = new HBox(12, mark, brand, new Region());
        HBox.setHgrow(topBar.getChildren().get(2), Priority.ALWAYS);
        topBar.getChildren().set(2, networkPill);
        return topBar;
    }

    private VBox createHero() {
        Label headline = styledLabel(
                "Your files. Your network. Your control.", "headline"
        );
        Label subtitle = styledLabel(
                "Send files directly between Windows PCs. No cloud uploads. No account.",
                "subtitle"
        );

        VBox hero = new VBox(8, headline, subtitle);
        hero.setPadding(new Insets(14, 0, 8, 0));
        return hero;
    }

    private HBox createActionCards() {
        VBox receiveCard = createReceiveCard();
        VBox sendCard = createSendCard();
        HBox cards = new HBox(16, receiveCard, sendCard);

        HBox.setHgrow(receiveCard, Priority.ALWAYS);
        HBox.setHgrow(sendCard, Priority.ALWAYS);
        return cards;
    }

    private VBox createNearbyPanel() {
        Label title = styledLabel("Nearby FilzaDrop devices", "section-title");

        nearbyDevices = new ListView<>();
        nearbyDevices.getSelectionModel().setSelectionMode(
                SelectionMode.MULTIPLE
        );
        nearbyDevices.setPlaceholder(
                new Label("No devices found yet")
        );
        nearbyDevices.setPrefHeight(150);
        nearbyDevices.setMinHeight(110);
        nearbyDevices.setMaxHeight(170);
        nearbyDevices.getStyleClass().add("device-list");

        deviceSearchField = new TextField();
        deviceSearchField.setPromptText("Search devices by name or IP...");
        deviceSearchField.setMaxWidth(Double.MAX_VALUE);
        deviceSearchField.getStyleClass().add("dark-field");
        deviceSearchField.textProperty().addListener(
                (obs, oldValue, newValue) -> refreshDeviceList()
        );

        nearbyDevices.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                String[] parts = item.split("\\|", 3);
                String name = parts.length > 0 ? parts[0] : item;
                String ip = parts.length > 1 ? parts[1] : "";
                String type = parts.length > 2 ? parts[2] : "LAN device";

                Label deviceName = new Label(name);
                deviceName.getStyleClass().add("device-name");

                Label address = new Label(ip + "  ·  " + type);
                address.getStyleClass().add("device-address");

                VBox details = new VBox(3, deviceName, address);
                details.setAlignment(Pos.CENTER_LEFT);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Label icon = new Label("●");
                icon.getStyleClass().add("device-dot");

                HBox row = new HBox(12, icon, details, spacer);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 12, 8, 12));

                setText(null);
                setGraphic(row);
            }
        });

        Label selectionHint = styledLabel(
                "Select multiple devices with Ctrl or Shift.", "muted-small"
        );
        sendSelectedButton = createPrimaryButton(
                "Send to Selected Devices", this::sendToSelectedDevices
        );

        scanLanButton = new Button("Scan LAN");
        scanLanButton.setMaxWidth(Double.MAX_VALUE);
        scanLanButton.getStyleClass().add("secondary-button");
        scanLanButton.setOnAction(event -> {
            scanLanButton.setDisable(true);
            setStatus("Scanning your local network…");

            LanScanner.scan(
                    ip -> Platform.runLater(() -> {
                        discoveredDevices.putIfAbsent(
                                "lan:" + ip, "LAN host|" + ip + "|LAN device"
                        );
                        refreshDeviceList();
                    }),
                    () -> Platform.runLater(() -> {
                        scanLanButton.setDisable(false);
                        setStatus("LAN scan finished.");
                    })
            );
        });

        VBox panel = new VBox(
                9, title, selectionHint, deviceSearchField, nearbyDevices,
                scanLanButton, sendSelectedButton
        );
        panel.getStyleClass().add("activity-panel");
        panel.setPadding(new Insets(14, 18, 14, 18));
        return panel;
    }

    private VBox createTransferPanel() {
        Label title = styledLabel("Transfer activity", "section-title");
        status = styledLabel("Ready when you are.", "activity");

        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("progress");

        progressText = styledLabel("No active transfer", "muted");

        VBox panel = new VBox(10, title, status, progress, progressText);
        panel.getStyleClass().add("panel");
        panel.setPadding(new Insets(18));
        return panel;
    }

    private Label createFooter() {
        return styledLabel(
                "FILZA • PHASE 1 / ENCRYPTED LOCAL TRANSFER", "footer"
        );
    }

    private Scene createScene(VBox root) {
        Scene scene = new Scene(root, 1000, 750);
        scene.getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm()
        );
        return scene;
    }

    private VBox createReceiveCard() {
        Label icon = styledLabel("↓", "card-icon");
        Label title = styledLabel("Receive files", "card-title");
        Label description = styledLabel(
                "Start a one-time receiver on this PC.", "desc"
        );
        Label codeCaption = styledLabel(
                "ONE-TIME PAIRING CODE", "field-label"
        );

        pairingCode = styledLabel(EMPTY_PAIRING_CODE, "code");

        String localIp = TransferService.getLocalIpv4Hint();
        Label ipHint = styledLabel(
                "Your IP: " + localIp + ":" + PORT, "muted-small"
        );
        ipHint.setWrapText(true);

        Button copyIpButton = createCopyIpButton(localIp);

        receivePath = styledLabel(
                "Saves to: " + TransferService.receiveDir(), "muted"
        );
        receivePath.setWrapText(true);

        receiveButton = createPrimaryButton(
                "Start receiving", this::startReceiving
        );

        VBox card = new VBox(
                11,
                icon,
                title,
                description,
                codeCaption,
                pairingCode,
                ipHint,
                copyIpButton,
                receivePath,
                receiveButton
        );
        styleCard(card);
        return card;
    }

    private VBox createSendCard() {
        Label icon = styledLabel("↑", "card-icon");
        Label title = styledLabel("Send a file", "card-title");
        Label description = styledLabel(
                "Choose a file and enter the receiving PC's details.", "desc"
        );
        Label ipCaption = styledLabel(
                "RECEIVER IP ADDRESS", "field-label"
        );
        Label codeCaption = styledLabel("PAIRING CODE", "field-label");

        ipField = new TextField();
        ipField.setPromptText("Example: 192.168.1.24");
        ipField.getStyleClass().add("field");

        codeField = new PasswordField();
        codeField.setPromptText("8-digit code");
        codeField.getStyleClass().add("field");
        codeField.textProperty().addListener(
                (observable, oldValue, newValue) -> keepPairingCodeDigits(newValue)
        );

        fileLabel = styledLabel(
                "Drop a file here or click to choose", "drop-zone"
        );
        fileLabel.setWrapText(true);
        fileLabel.setMinHeight(62);
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        configureFileDropZone();

        Button chooseButton = createSecondaryButton(
                "Choose file", this::chooseFile
        );
        sendButton = createPrimaryButton("Send securely", this::sendFile);

        HBox actions = new HBox(10, chooseButton, sendButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(
                10,
                icon,
                title,
                description,
                ipCaption,
                ipField,
                codeCaption,
                codeField,
                fileLabel,
                actions
        );
        styleCard(card);
        return card;
    }

    private void configureFileDropZone() {
        fileLabel.setOnMouseClicked(event -> {
            if (!busy) {
                chooseFile();
            }
        });
        fileLabel.setOnDragOver(event -> {
            if (!busy && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        fileLabel.setOnDragDropped(event -> {
            boolean accepted = !busy && event.getDragboard().hasFiles();
            if (accepted) {
                setFile(event.getDragboard().getFiles().get(0).toPath());
            }
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    private void startReceiving() {
        if (busy) {
            return;
        }

        try {
            server = new ServerSocket(PORT);
            String code = TransferService.newCode();

            pairingCode.setText(code);
            setTransferBusy(true);
            animate(receiveButton);
            setStatus(
                    "Listening. Share the IP and code only with the intended sender."
            );
            showIndeterminateProgress("Waiting for one incoming transfer...");

            Task<Path> task = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    return TransferService.receive(
                            server,
                            code,
                            TransferService.receiveDir(),
                            bytes -> Platform.runLater(() -> progressUpdate(bytes))
                    );
                }
            };

            task.setOnSucceeded(event -> {
                Path receivedFile = task.getValue();
                finishTransfer();
                progress.setProgress(1);
                progressText.setText("Saved: " + receivedFile);
                setStatus("Transfer complete. Authentication verified.");
                alert(
                        Alert.AlertType.INFORMATION,
                        "File received",
                        "Saved to:\n" + receivedFile
                );
            });
            task.setOnFailed(event -> handleReceiveFailure(task));
            startTask(task, "filzadrop-receiver");
        } catch (IOException exception) {
            setStatus("Cannot open port " + PORT + ": " + exception.getMessage());
            alert(
                    Alert.AlertType.ERROR,
                    "Could not receive",
                    "Port may already be in use.\n" + exception.getMessage()
            );
        }
    }

    private void handleReceiveFailure(Task<Path> task) {
        Throwable exception = task.getException();
        finishTransfer();
        progress.setProgress(0);
        progressText.setText("Transfer did not complete.");
        setStatus("Receive failed: " + message(exception));
        alert(
                Alert.AlertType.WARNING,
                "Receive failed",
                message(exception) + "\n\nStart receiving again to retry."
        );
    }

    private void sendFile() {
        if (busy) {
            return;
        }
        if (chosenFile == null || !Files.isRegularFile(chosenFile)) {
            alert(Alert.AlertType.WARNING, "Choose a file", "Choose a file first.");
            return;
        }

        String host = ipField.getText().trim();
        String code = codeField.getText().trim();
        if (host.isBlank() || code.length() != 8) {
            alert(
                    Alert.AlertType.WARNING,
                    "Receiver details needed",
                    "Enter the receiver's local IP and 8-digit code."
            );
            return;
        }

        Path file = chosenFile;
        setTransferBusy(true);
        showIndeterminateProgress("Connecting...");
        setStatus("Connecting to " + host + ":" + PORT);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                TransferService.send(
                        file,
                        host,
                        PORT,
                        code,
                        bytes -> Platform.runLater(() -> progressUpdate(bytes))
                );
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            finishTransfer();
            progress.setProgress(1);
            progressText.setText("Sent: " + file.getFileName());
            setStatus(
                    "Send complete. The receiver verifies the payload before saving."
            );
        });
        task.setOnFailed(event -> handleSendFailure(task));
        startTask(task, "filzadrop-sender");
    }

    private void handleSendFailure(Task<Void> task) {
        Throwable exception = task.getException();
        finishTransfer();
        progress.setProgress(0);
        progressText.setText("Transfer did not complete.");
        setStatus("Send failed: " + message(exception));
        alert(
                Alert.AlertType.WARNING,
                "Send failed",
                message(exception)
                        + "\n\nCheck the receiver, IP, pairing code, and Windows Firewall."
        );
    }

    private record TransferTarget(
            String deviceName,
            String host,
            int port,
            String pairingCode
    ) {
    }

    private void sendToSelectedDevices() {
        if (busy) {
            return;
        }

        if (chosenFile == null || !Files.isRegularFile(chosenFile)) {
            chooseFile();
            if (chosenFile == null || !Files.isRegularFile(chosenFile)) {
                return;
            }
        }

        List<String> selectedDevices = new ArrayList<>(
                nearbyDevices.getSelectionModel().getSelectedItems()
        );
        if (selectedDevices.isEmpty()) {
            alert(
                    Alert.AlertType.INFORMATION,
                    "Select devices",
                    "Select one or more nearby devices first."
            );
            return;
        }

        List<TransferTarget> targets = collectTransferTargets(selectedDevices);
        if (targets.isEmpty()) {
            return;
        }

        Path fileToSend = chosenFile;
        setTransferBusy(true);
        showIndeterminateProgress(
                "Sending to " + targets.size() + " device(s)..."
        );

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                return sendSequentially(fileToSend, targets);
            }
        };

        task.setOnSucceeded(event -> showBatchResults(task.getValue(), targets));
        task.setOnFailed(event -> {
            Throwable exception = task.getException();
            finishTransfer();
            setStatus("Batch transfer failed.");
            alert(
                    Alert.AlertType.ERROR,
                    "Transfer failed",
                    message(exception)
            );
        });
        startTask(task, "filzadrop-multi-sender");
    }

    private List<TransferTarget> collectTransferTargets(
            List<String> selectedDevices
    ) {
        List<TransferTarget> targets = new ArrayList<>();

        for (String device : selectedDevices) {
            Optional<TransferTarget> target = promptForTransferTarget(device);
            if (target.isEmpty()) {
                return List.of();
            }
            targets.add(target.get());
        }

        return targets;
    }

    private Optional<TransferTarget> promptForTransferTarget(String device) {
        try {
            String[] parts = device.split("\\|", 3);
            if (parts.length < 3 || !"FilzaDrop".equals(parts[2])) {
                alert(
                        Alert.AlertType.INFORMATION,
                        "FilzaDrop app required",
                        "This is only a reachable LAN host. It must run "
                                + "FilzaDrop before it can receive files."
                );
                return Optional.empty();
            }

            String name = parts[0];
            String address = parts[1];
            int colon = address.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Device port is missing.");
            }

            String host = address.substring(0, colon).trim();
            int port = Integer.parseInt(address.substring(colon + 1).trim());
            if (!isValidIpv4(host) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid device address.");
            }

            TextInputDialog dialog = new TextInputDialog();
            dialog.initOwner(stage);
            dialog.setTitle("Receiver pairing code");
            dialog.setHeaderText("Send to " + name + " (" + host + ")");
            dialog.setContentText("Enter the 8-digit code shown on that PC:");

            Optional<String> answer = dialog.showAndWait();
            if (answer.isEmpty()) {
                return Optional.empty();
            }

            String code = answer.get().trim();
            if (!code.matches("\\d{8}")) {
                alert(
                        Alert.AlertType.WARNING,
                        "Invalid pairing code",
                        "The code must contain exactly 8 digits."
                );
                return Optional.empty();
            }

            return Optional.of(new TransferTarget(name, host, port, code));
        } catch (RuntimeException exception) {
            alert(
                    Alert.AlertType.WARNING,
                    "Invalid device",
                    "Could not read this device:\n" + device
            );
            return Optional.empty();
        }
    }

    private boolean isValidIpv4(String host) {
        return host.matches("(\\d{1,3}\\.){3}\\d{1,3}");
    }

    private List<String> sendSequentially(
            Path file,
            List<TransferTarget> targets
    ) {
        List<String> results = new ArrayList<>();

        for (int index = 0; index < targets.size(); index++) {
            TransferTarget target = targets.get(index);
            int current = index + 1;

            Platform.runLater(() -> {
                setStatus(
                        "Sending to " + target.deviceName() + " ("
                                + current + "/" + targets.size() + ")"
                );
                progressText.setText(
                        "Device " + current + " of " + targets.size()
                                + " — " + target.deviceName()
                );
            });

            try {
                TransferService.send(
                        file,
                        target.host(),
                        target.port(),
                        target.pairingCode(),
                        bytes -> Platform.runLater(() -> {
                            progress.setProgress(
                                    ProgressBar.INDETERMINATE_PROGRESS
                            );
                            progressText.setText(
                                    target.deviceName() + ": "
                                            + TransferService.bytes(bytes)
                                            + " sent"
                            );
                        })
                );
                results.add("SUCCESS — " + target.deviceName());
            } catch (Exception exception) {
                results.add(
                        "FAILED — " + target.deviceName() + ": "
                                + message(exception)
                );
            }
        }

        return results;
    }

    private void showBatchResults(
            List<String> results,
            List<TransferTarget> targets
    ) {
        finishTransfer();

        long successes = results.stream()
                .filter(result -> result.startsWith("SUCCESS"))
                .count();
        progress.setProgress((double) successes / targets.size());
        progressText.setText(
                successes + " of " + targets.size() + " transfers succeeded"
        );

        boolean allSucceeded = successes == targets.size();
        setStatus(
                "Batch finished: " + successes + " of "
                        + targets.size() + " successful."
        );
        alert(
                allSucceeded
                        ? Alert.AlertType.INFORMATION
                        : Alert.AlertType.WARNING,
                "FilzaDrop transfer results",
                String.join("\n", results)
        );
    }

    private void startTask(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a file to send");
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            setFile(file.toPath());
        }
    }

    private void setFile(Path path) {
        chosenFile = path;
        try {
            fileLabel.setText(
                    path.getFileName()
                            + "\n"
                            + TransferService.bytes(Files.size(path))
                            + " • click/drop to replace"
            );
            animate(fileLabel);
        } catch (IOException exception) {
            fileLabel.setText("Unable to read selected file.");
        }
    }

    private void keepPairingCodeDigits(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() > 8) {
            digits = digits.substring(0, 8);
        }
        if (!digits.equals(value)) {
            codeField.setText(digits);
        }
    }

    private void progressUpdate(long bytes) {
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressText.setText(TransferService.bytes(bytes) + " transferred");
    }

    private void showIndeterminateProgress(String description) {
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressText.setText(description);
    }

    private void setTransferBusy(boolean transferBusy) {
        busy = transferBusy;
        receiveButton.setDisable(transferBusy);
        sendButton.setDisable(transferBusy);
        if (sendSelectedButton != null) {
            sendSelectedButton.setDisable(transferBusy);
        }
    }

    private void finishTransfer() {
        setTransferBusy(false);
        closeServer();
        pairingCode.setText(EMPTY_PAIRING_CODE);
        receivePath.setText("Saves to: " + TransferService.receiveDir());
    }

    private void closeServer() {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // The transfer has already ended; there is nothing else to recover.
        } finally {
            server = null;
        }
    }

    private void startDiscovery() {
        String deviceName = System.getProperty("user.name") + "-PC";
        discoveryService = new LanDiscoveryService(
                deviceName,
                message -> Platform.runLater(() -> {
                    int separator = message.lastIndexOf(" — ");
                    if (separator < 0 || nearbyDevices == null) {
                        return;
                    }

                    String name = message.substring(0, separator).trim();
                    String address = message.substring(separator + 3).trim();
                    discoveredDevices.put(
                            "filzadrop:" + address.toLowerCase(
                                    java.util.Locale.ROOT
                            ),
                            name + "|" + address + "|FilzaDrop"
                    );
                    refreshDeviceList();
                })
        );
        discoveryService.start();
    }

    private void refreshDeviceList() {
        if (nearbyDevices == null) {
            return;
        }

        String filter = deviceSearchField == null
                ? ""
                : deviceSearchField.getText().trim()
                        .toLowerCase(java.util.Locale.ROOT);

        var selected = new java.util.HashSet<>(
                nearbyDevices.getSelectionModel().getSelectedItems()
        );

        var visible = discoveredDevices.values().stream()
                .filter(item -> filter.isBlank()
                        || item.toLowerCase(java.util.Locale.ROOT)
                                .contains(filter))
                .sorted()
                .toList();

        nearbyDevices.getItems().setAll(visible);

        for (String item : visible) {
            if (selected.contains(item)) {
                nearbyDevices.getSelectionModel().select(item);
            }
        }
    }

    private Button createCopyIpButton(String ip) {
        Button button = createSecondaryButton("Copy IP", () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(ip);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("Your IP address was copied.");
        });
        return button;
    }

    private Button createPrimaryButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("primary", "wide");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button createSecondaryButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void styleCard(VBox card) {
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        card.setMinHeight(335);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void animate(Node node) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(140), node);
        scale.setFromX(0.98);
        scale.setFromY(0.98);
        scale.setToX(1);
        scale.setToY(1);
        scale.play();
    }

    private void playEntranceAnimation(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(450), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private void alert(Alert.AlertType type, String title, String text) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(text);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm()
        );
        alert.show();
    }

    private String message(Throwable exception) {
        if (exception == null || exception.getMessage() == null) {
            return "Unknown error";
        }
        return exception.getMessage();
    }

    @Override
    public void stop() {
        closeServer();
        if (discoveryService != null) {
            discoveryService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
