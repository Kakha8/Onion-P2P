package kakha.kudava.onionp2p;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
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
            refreshMessages();
        });

        transcript.setEditable(false);
        transcript.setWrapText(true);
        composer.setPromptText("Write a message…");
        composer.setOnAction(event -> send());
        sendButton.setOnAction(event -> send());
        sendButton.setDisable(true);
        HBox inputRow = new HBox(8, composer, sendButton);
        HBox.setHgrow(composer, Priority.ALWAYS);

        Label securityNote = new Label("Messages are encrypted between contacts. Local chat history is currently unencrypted.");
        securityNote.setWrapText(true);
        VBox conversation = new VBox(8, transcript, status, inputRow, securityNote);
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
                                .append(message.outgoing() ? "You" : contact.username()).append(": ")
                                .append(message.body()).append('\n');
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
        sendButton.setDisable(true);
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
                    status.setText("Message delivered and saved.");
                    sendButton.setDisable(false);
                    refreshMessages();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    composer.setDisable(false);
                    sending = false;
                    sendButton.setDisable(false);
                    status.setText("Message was not delivered: " + safe(e));
                });
            }
        });
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? "connection unavailable" : e.getMessage();
    }
}
