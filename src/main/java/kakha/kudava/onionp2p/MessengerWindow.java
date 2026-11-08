package kakha.kudava.onionp2p;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small standalone chat window backed by the saved contacts and local message history. */
final class MessengerWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final ContactManager manager;
    private final Stage stage = new Stage();
    private final ListView<ContactStore.Contact> contactList = new ListView<>();
    private final ListView<MessageStore.Message> transcript = new ListView<>();
    private final TextField composer = new TextField();
    private final Button sendButton = new Button("Send");
    private final Button attachButton = new Button("Attach file");
    private final ProgressBar transferProgress = new ProgressBar(0);
    private final Label status = new Label("Select a saved contact to open a conversation.");
    private final AtomicBoolean loading = new AtomicBoolean();
    private final Timeline poll;
    private ContactStore.Contact selected;
    private boolean sending;

    private MessengerWindow(Window owner, ContactManager manager) {
        this.manager = manager;
        stage.setTitle("Onion-P2P Messenger");
        if (owner != null) stage.initOwner(owner);
        stage.setMinWidth(720); stage.setMinHeight(480);

        contactList.setPlaceholder(new Label("No saved contacts yet."));
        contactList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ContactStore.Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                setText(empty || contact == null ? null : contact.username() + "\n" + contact.onion());
            }
        });
        contactList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            selected = value;
            transcript.getItems().clear();
            setTransferControls(false);
            refreshMessages();
        });

        transcript.setPlaceholder(new Label("No messages yet."));
        transcript.setCellFactory(list -> new MessageCell());
        composer.setPromptText("Write a message…");
        composer.setOnAction(event -> send());
        sendButton.setOnAction(event -> send());
        attachButton.setOnAction(event -> chooseFile());
        setTransferControls(false);
        transferProgress.setMaxWidth(Double.MAX_VALUE);
        transferProgress.setVisible(false);
        transferProgress.setManaged(false);
        HBox inputRow = new HBox(8, composer, attachButton, sendButton);
        HBox.setHgrow(composer, Priority.ALWAYS);

        Label securityNote = new Label("Messages and files are encrypted in transit. Files up to 1 GiB. Received files save in the app downloads folder; use Export… to choose another location. Local chat history is unencrypted.");
        securityNote.setWrapText(true);
        VBox conversation = new VBox(8, transcript, status, inputRow, transferProgress, securityNote);
        conversation.setPadding(new Insets(12));
        VBox.setVgrow(transcript, Priority.ALWAYS);
        BorderPane root = new BorderPane(conversation, null, null, null, contactList);
        root.setPadding(new Insets(8));
        BorderPane.setMargin(contactList, new Insets(4, 8, 4, 4));
        contactList.setPrefWidth(220);
        stage.setScene(new Scene(root, 820, 560));

        poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshMessages()));
        poll.setCycleCount(Timeline.INDEFINITE);
        stage.setOnShown(e -> { loadContacts(); poll.play(); });
        stage.setOnHidden(e -> poll.stop());
    }

    static void show(Window owner, ContactManager manager) {
        var window = new MessengerWindow(owner, manager);
        window.stage.show();
    }

    private void loadContacts() {
        Thread.ofPlatform().daemon().name("messenger-contacts").start(() -> {
            try {
                List<ContactStore.Contact> contacts = manager.contacts();
                Platform.runLater(() -> {
                    ContactStore.Contact previous = selected;
                    contactList.setItems(FXCollections.observableArrayList(contacts));
                    if (previous != null) {
                        contacts.stream().filter(c -> c.link().equals(previous.link())).findFirst()
                                .ifPresent(c -> contactList.getSelectionModel().select(c));
                    }
                    if (contactList.getSelectionModel().getSelectedItem() == null && !contacts.isEmpty())
                        contactList.getSelectionModel().selectFirst();
                    if (contacts.isEmpty()) status.setText("Pair with a contact first, then reopen Messenger.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load contacts: " + safe(e)));
            }
        });
    }

    private void refreshMessages() {
        ContactStore.Contact contact = selected;
        if (contact == null || !loading.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("messenger-history").start(() -> {
            try {
                var messages = manager.messages(contact.link());
                Platform.runLater(() -> {
                    if (selected == null || !selected.link().equals(contact.link())) return;
                    transcript.setItems(FXCollections.observableArrayList(messages));
                    if (!messages.isEmpty()) transcript.scrollTo(messages.size() - 1);
                    sendButton.setDisable(sending);
                    if (!sending) status.setText("Paired with " + contact.username());
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load conversation: " + safe(e)));
            } finally { loading.set(false); }
        });
    }

    private void send() {
        ContactStore.Contact contact = selected;
        String body = composer.getText();
        if (contact == null || body == null || body.isBlank() || sending) return;
        sending = true;
        setTransferControls(true);
        composer.setDisable(true);
        status.setText("Sending message…");
        Thread.ofPlatform().daemon().name("messenger-send").start(() -> {
            try {
                manager.send(contact.link(), body, message -> Platform.runLater(() -> {
                    if (sending && selected != null && selected.link().equals(contact.link())) status.setText(message);
                }));
                Platform.runLater(() -> {
                    if (selected != null && selected.link().equals(contact.link()) && composer.getText().equals(body))
                        composer.clear();
                    composer.setDisable(false);
                    sending = false;
                    setTransferControls(false);
                    status.setText("Message delivered and saved.");
                    refreshMessages();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    composer.setDisable(false);
                    sending = false;
                    setTransferControls(false);
                    status.setText("Message was not delivered: " + safe(e));
                });
            }
        });
    }

    private void chooseFile() {
        if (sending) return;
        ContactStore.Contact contact = selected;
        if (contact == null) {
            status.setText("Select a saved contact before attaching a file.");
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("Choose a file to send (1 GiB maximum)");
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        sending = true;
        setTransferControls(true);
        transferProgress.setProgress(0);
        transferProgress.setManaged(true);
        transferProgress.setVisible(true);
        status.setText("Sending " + file.getName() + "…");
        Thread.ofPlatform().daemon().name("messenger-file-send").start(() -> {
            try {
                long size = file.length();
                manager.sendFile(contact.link(), file.toPath(), transferred -> Platform.runLater(() ->
                                transferProgress.setProgress(size == 0 ? 1.0 : (double) transferred / size)),
                        message -> Platform.runLater(() -> {
                            if (sending && selected != null && selected.link().equals(contact.link())) status.setText(message);
                        }));
                Platform.runLater(() -> {
                    sending = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    status.setText("File delivered and saved by " + contact.username() + ".");
                    refreshMessages();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sending = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    status.setText("File was not delivered: " + safe(e));
                });
            }
        });
    }

    private void export(MessageStore.Message message) {
        if (sending || message.attachmentPath() == null) return;
        Path source = Path.of(message.attachmentPath());
        var chooser = new FileChooser();
        chooser.setTitle("Export received attachment");
        chooser.setInitialFileName(message.attachmentName());
        File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) return;
        Path destination = chosen.toPath().toAbsolutePath().normalize();
        if (Files.exists(destination)) {
            status.setText("That file already exists. Choose a different export name.");
            return;
        }
        sending = true;
        setTransferControls(true);
        transferProgress.setProgress(0);
        transferProgress.setManaged(true);
        transferProgress.setVisible(true);
        status.setText("Exporting " + message.attachmentName() + "…");
        Thread.ofPlatform().daemon().name("messenger-file-export").start(() -> {
            Path temporary = null;
            try {
                Path parent = destination.getParent();
                if (parent == null || !Files.isDirectory(parent)) throw new java.io.IOException("Choose an existing folder.");
                temporary = Files.createTempFile(parent, ".onion-export-", ".part");
                long copied = 0;
                try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    long lastReported = 0;
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        copied += count;
                        if (copied - lastReported >= 1024 * 1024 || copied == message.attachmentSize()) {
                            long total = copied;
                            Platform.runLater(() -> transferProgress.setProgress(message.attachmentSize() == 0
                                    ? 1 : (double) total / message.attachmentSize()));
                            lastReported = copied;
                        }
                    }
                }
                if (copied != message.attachmentSize()) throw new java.io.IOException("The received file size changed or is incomplete.");
                Files.move(temporary, destination);
                temporary = null;
                Platform.runLater(() -> {
                    sending = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    status.setText("Exported to " + destination);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sending = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    status.setText("Could not export the file: " + safe(e));
                });
            } finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            }
        });
    }

    private void setTransferControls(boolean busy) {
        sendButton.setDisable(busy || selected == null);
        attachButton.setDisable(busy || selected == null);
        composer.setDisable(busy || selected == null);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? "connection unavailable" : e.getMessage();
    }

    private final class MessageCell extends ListCell<MessageStore.Message> {
        @Override protected void updateItem(MessageStore.Message message, boolean empty) {
            super.updateItem(message, empty);
            if (empty || message == null) { setText(null); setGraphic(null); return; }
            String who = message.outgoing() ? "You" : selected == null ? "Contact" : selected.username();
            Label heading = new Label("[" + TIME.format(Instant.ofEpochMilli(message.timestamp())) + "] " + who);
            Label content;
            if (message.attachmentName() != null) {
                content = new Label("📎 " + message.attachmentName() + " (" + formatBytes(message.attachmentSize()) + ")");
                content.setWrapText(true);
                if (!message.outgoing()) {
                    Hyperlink exportLink = new Hyperlink("Export…");
                    exportLink.setOnAction(event -> export(message));
                    VBox rows = new VBox(4, heading, content, exportLink);
                    rows.setPadding(new Insets(6));
                    setText(null); setGraphic(rows); return;
                }
            } else {
                content = new Label(message.body());
                content.setWrapText(true);
            }
            VBox rows = new VBox(4, heading, content);
            rows.setPadding(new Insets(6));
            setText(null); setGraphic(rows);
        }
    }
}
