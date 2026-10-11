package dev.filza.filzadrop;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

public class FilzaDropApp extends Application {
    private static final int PORT = 45821;
    private Stage stage;
    private Label status, pairingCode, fileLabel, receivePath, progressText;
    private ProgressBar progress;
    private TextField ipField;
    private PasswordField codeField;
    private Button receiveButton, sendButton;
    private Path chosenFile;
    private ServerSocket server;
    private boolean busy;

    @Override public void start(Stage stage) {
        this.stage = stage;
        Label mark = new Label("F"); mark.getStyleClass().add("logo");
        Label name = new Label("FilzaDrop"); name.getStyleClass().add("brand");
        Label sub = new Label("PRIVATE FILE TRANSFER"); sub.getStyleClass().add("eyebrow");
        VBox brandText = new VBox(2, name, sub);
        HBox top = new HBox(12, mark, brandText, new Region()); HBox.setHgrow(top.getChildren().get(2), Priority.ALWAYS);
        Label pill = new Label("●  LOCAL NETWORK"); pill.getStyleClass().add("pill"); top.getChildren().set(2, pill);
        Label headline = new Label("Your files. Your network. Your control."); headline.getStyleClass().add("headline");
        Label subtitle = new Label("Send files directly between Windows PCs. No cloud uploads. No account."); subtitle.getStyleClass().add("subtitle");
        VBox hero = new VBox(8, headline, subtitle); hero.setPadding(new Insets(14, 0, 8, 0));
        VBox receive = receiveCard(), send = sendCard(); HBox cards = new HBox(16, receive, send); HBox.setHgrow(receive, Priority.ALWAYS); HBox.setHgrow(send, Priority.ALWAYS);
        Label activityTitle = new Label("Transfer activity"); activityTitle.getStyleClass().add("section-title");
        status = new Label("Ready when you are."); status.getStyleClass().add("activity");
        progress = new ProgressBar(0); progress.setMaxWidth(Double.MAX_VALUE); progress.getStyleClass().add("progress");
        progressText = new Label("No active transfer"); progressText.getStyleClass().add("muted");
        VBox activity = new VBox(10, activityTitle, status, progress, progressText); activity.getStyleClass().add("panel"); activity.setPadding(new Insets(18));
        Label footer = new Label("FILZA • PHASE 1 / ENCRYPTED LOCAL TRANSFER"); footer.getStyleClass().add("footer");
        VBox root = new VBox(18, top, hero, cards, activity, footer); root.setPadding(new Insets(24, 28, 18, 28)); root.getStyleClass().add("root");
        Scene scene = new Scene(root, 1000, 750); scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setTitle("FilzaDrop"); stage.setMinWidth(850); stage.setMinHeight(650); stage.setScene(scene); stage.show();
        FadeTransition fade = new FadeTransition(Duration.millis(450), root); fade.setFromValue(0); fade.setToValue(1); fade.play();
    }

    private VBox receiveCard() {
        Label icon = new Label("↓"); icon.getStyleClass().add("card-icon");
        Label title = new Label("Receive files"); title.getStyleClass().add("card-title");
        Label desc = new Label("Start a one-time receiver on this PC."); desc.getStyleClass().add("desc");
        Label cap = new Label("ONE-TIME PAIRING CODE"); cap.getStyleClass().add("field-label");
        pairingCode = new Label("────────"); pairingCode.getStyleClass().add("code");
        Label ip = new Label("This PC: " + TransferService.localIp() + ":" + PORT); ip.getStyleClass().add("muted"); ip.setWrapText(true);
        receivePath = new Label("Saves to: " + TransferService.receiveDir()); receivePath.getStyleClass().add("muted"); receivePath.setWrapText(true);
        receiveButton = new Button("Start receiving"); receiveButton.getStyleClass().addAll("primary", "wide"); receiveButton.setOnAction(e -> startReceiving());
        VBox box = new VBox(12, icon, title, desc, cap, pairingCode, ip, receivePath, receiveButton); box.getStyleClass().add("card"); box.setPadding(new Insets(22)); box.setMinHeight(335); return box;
    }

    private VBox sendCard() {
        Label icon = new Label("↑"); icon.getStyleClass().add("card-icon");
        Label title = new Label("Send a file"); title.getStyleClass().add("card-title");
        Label desc = new Label("Choose a file and enter the receiving PC's details."); desc.getStyleClass().add("desc");
        Label ipCap = new Label("RECEIVER IP ADDRESS"); ipCap.getStyleClass().add("field-label");
        ipField = new TextField(); ipField.setPromptText("Example: 192.168.1.24"); ipField.getStyleClass().add("field");
        Label codeCap = new Label("PAIRING CODE"); codeCap.getStyleClass().add("field-label");
        codeField = new PasswordField(); codeField.setPromptText("8-digit code"); codeField.getStyleClass().add("field");
        codeField.textProperty().addListener((o, old, val) -> { String digits = val.replaceAll("\\D", ""); if (!digits.equals(val)) codeField.setText(digits); if (digits.length() > 8) codeField.setText(digits.substring(0, 8)); });
        fileLabel = new Label("Drop a file here or click to choose"); fileLabel.getStyleClass().add("drop-zone"); fileLabel.setWrapText(true); fileLabel.setMinHeight(62); fileLabel.setMaxWidth(Double.MAX_VALUE);
        fileLabel.setOnMouseClicked(e -> { if (!busy) chooseFile(); });
        fileLabel.setOnDragOver(e -> { if (!busy && e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY); e.consume(); });
        fileLabel.setOnDragDropped(e -> { boolean ok = !busy && e.getDragboard().hasFiles(); if (ok) setFile(e.getDragboard().getFiles().get(0).toPath()); e.setDropCompleted(ok); e.consume(); });
        Button choose = new Button("Choose file"); choose.getStyleClass().add("secondary"); choose.setOnAction(e -> chooseFile());
        sendButton = new Button("Send securely"); sendButton.getStyleClass().addAll("primary", "wide"); sendButton.setOnAction(e -> sendFile());
        HBox actions = new HBox(10, choose, sendButton); actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, icon, title, desc, ipCap, ipField, codeCap, codeField, fileLabel, actions); box.getStyleClass().add("card"); box.setPadding(new Insets(22)); box.setMinHeight(335); return box;
    }

    private void startReceiving() {
        if (busy) return;
        try {
            server = new ServerSocket(PORT);
            String code = TransferService.newCode(); pairingCode.setText(code); receiveButton.setDisable(true); sendButton.setDisable(true); busy = true;
            animate(receiveButton); setStatus("Listening. Share the IP and code only with the intended sender.");
            progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS); progressText.setText("Waiting for one incoming transfer…");
            Task<Path> task = new Task<>() { @Override protected Path call() throws Exception { return TransferService.receive(server, code, TransferService.receiveDir(), b -> Platform.runLater(() -> progressUpdate(b))); } };
            task.setOnSucceeded(e -> { finish(); progress.setProgress(1); progressText.setText("Saved: " + task.getValue()); setStatus("Transfer complete. Authentication verified."); alert(Alert.AlertType.INFORMATION, "File received", "Saved to:\n" + task.getValue()); });
            task.setOnFailed(e -> { Throwable ex = task.getException(); finish(); progress.setProgress(0); progressText.setText("Transfer did not complete."); setStatus("Receive failed: " + message(ex)); alert(Alert.AlertType.WARNING, "Receive failed", message(ex) + "\n\nStart receiving again to retry."); });
            Thread t = new Thread(task, "filzadrop-receiver"); t.setDaemon(true); t.start();
        } catch (IOException ex) { setStatus("Cannot open port " + PORT + ": " + ex.getMessage()); alert(Alert.AlertType.ERROR, "Could not receive", "Port may already be in use.\n" + ex.getMessage()); }
    }

    private void sendFile() {
        if (busy) return;
        if (chosenFile == null || !Files.isRegularFile(chosenFile)) { alert(Alert.AlertType.WARNING, "Choose a file", "Choose a file first."); return; }
        String host = ipField.getText().trim(), code = codeField.getText().trim();
        if (host.isBlank() || code.length() != 8) { alert(Alert.AlertType.WARNING, "Receiver details needed", "Enter the receiver's local IP and 8-digit code."); return; }
        busy = true; sendButton.setDisable(true); receiveButton.setDisable(true); progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS); progressText.setText("Connecting…"); setStatus("Connecting to " + host + ":" + PORT);
        Path file = chosenFile;
        Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { TransferService.send(file, host, PORT, code, b -> Platform.runLater(() -> progressUpdate(b))); return null; } };
        task.setOnSucceeded(e -> { finish(); progress.setProgress(1); progressText.setText("Sent: " + file.getFileName()); setStatus("Send complete. The receiver verifies the payload before saving."); });
        task.setOnFailed(e -> { Throwable ex = task.getException(); finish(); progress.setProgress(0); progressText.setText("Transfer did not complete."); setStatus("Send failed: " + message(ex)); alert(Alert.AlertType.WARNING, "Send failed", message(ex) + "\n\nCheck the receiver, IP, pairing code, and Windows Firewall."); });
        Thread t = new Thread(task, "filzadrop-sender"); t.setDaemon(true); t.start();
    }

    private void chooseFile() { FileChooser fc = new FileChooser(); fc.setTitle("Choose a file to send"); java.io.File f = fc.showOpenDialog(stage); if (f != null) setFile(f.toPath()); }
    private void setFile(Path path) { chosenFile = path; try { fileLabel.setText(path.getFileName() + "\n" + TransferService.bytes(Files.size(path)) + " • click/drop to replace"); animate(fileLabel); } catch (IOException ex) { fileLabel.setText("Unable to read selected file."); } }
    private void progressUpdate(long bytes) { progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS); progressText.setText(TransferService.bytes(bytes) + " transferred"); }
    private void finish() { busy = false; receiveButton.setDisable(false); sendButton.setDisable(false); if (server != null) { try { server.close(); } catch (IOException ignored) {} server = null; } pairingCode.setText("────────"); receivePath.setText("Saves to: " + TransferService.receiveDir()); }
    private void setStatus(String text) { status.setText(text); }
    private void animate(javafx.scene.Node node) { ScaleTransition s = new ScaleTransition(Duration.millis(140), node); s.setFromX(.98); s.setFromY(.98); s.setToX(1); s.setToY(1); s.play(); }
    private void alert(Alert.AlertType type, String title, String text) { Alert a = new Alert(type); a.initOwner(stage); a.setTitle(title); a.setHeaderText(null); a.setContentText(text); a.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm()); a.show(); }
    private String message(Throwable ex) { return ex == null || ex.getMessage() == null ? "Unknown error" : ex.getMessage(); }
    @Override public void stop() { if (server != null) try { server.close(); } catch (IOException ignored) {} }
    public static void main(String[] args) { launch(args); }
}
