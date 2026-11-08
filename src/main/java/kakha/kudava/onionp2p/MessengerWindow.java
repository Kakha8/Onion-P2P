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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small standalone chat window backed by the saved contacts and local message history. */
final class MessengerWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final ContactManager manager;
    private final Stage stage = new Stage();
    private final ListView<ContactStore.Contact> contactList = new ListView<>();
    private final TextArea transcript = new TextArea();
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
            transcript.clear();
            setTransferControls(false);
            refreshMessages();
        });

        transcript.setEditable(false);
        transcript.setWrapText(true);
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

        Label securityNote = new Label("Messages and files are encrypted in transit. Files up to 10 MiB save under the app's downloads folder. Local chat history is unencrypted.");
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
                    StringBuilder text = new StringBuilder();
                    for (var message : messages) {
                        text.append('[').append(TIME.format(Instant.ofEpochMilli(message.timestamp()))).append("] ")
                                .append(message.outgoing() ? "You" : contact.username()).append(": ");
                        if (message.attachmentName() != null) {
                            text.append("📎 ").append(message.attachmentName()).append(" (")
                                    .append(formatBytes(message.attachmentSize())).append(')');
                            if (!message.outgoing()) text.append(" — saved to ").append(message.attachmentPath());
                            text.append('\n');
                        } else text.append(message.body()).append('\n');
                    }
                    String rendered = text.toString();
                    if (!transcript.getText().equals(rendered)) {
                        transcript.setText(rendered);
                        transcript.positionCaret(rendered.length());
                    }
                    sendButton.setDisable(sending);
                    status.setText("Paired with " + contact.username());
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
                manager.send(contact.link(), body);
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
        chooser.setTitle("Choose a file to send (10 MiB maximum)");
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
                        transferProgress.setProgress(size == 0 ? 1.0 : (double) transferred / size)));
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
}
