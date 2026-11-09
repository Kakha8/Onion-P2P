package kakha.kudava.onionp2p;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Embedded chat pane backed by saved contacts and local message history. */
final class MessengerPane extends BorderPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final ContactManager manager;
    private final ListView<ContactStore.Contact> contactList = new ListView<>();
    private final ListView<MessageStore.Message> transcript = new ListView<>();
    private final Label conversationTitle = new Label("Select a contact");
    private final Label conversationPresence = new Label();
    private final TextField composer = new TextField();
    private final Button sendButton = new Button();
    private final Button attachButton = new Button("📎");
    private final Button voiceButton = new Button();
    private final Button discardVoiceButton = new Button();
    private final Button pauseVoiceButton = new Button();
    private final Button voiceSendButton = new Button();
    private final TextArea attachmentCaption = new TextArea();
    private final HBox attachmentFilesList = new HBox(10);
    private final ScrollPane attachmentFilesViewport = new ScrollPane(attachmentFilesList);
    private final Button attachmentAddButton = new Button();
    private final Button attachmentSendButton = new Button();
    private final Label voiceDurationLabel = new Label("0:00");
    private final WaveformView voiceWaveform = new WaveformView();
    private final ProgressBar transferProgress = new ProgressBar(0);
    private final Label transferRate = new Label();
    private final Button cancelFileButton = new Button("×");
    private final Label status = new Label("Select a saved contact to open a conversation.");
    private final AtomicBoolean loading = new AtomicBoolean();
    private final ExecutorService presenceWorkers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "contact-presence"); thread.setDaemon(true); return thread;
    });
    private final java.util.Set<String> checkingPresence = ConcurrentHashMap.newKeySet();
    private final Map<String, Presence> presence = new ConcurrentHashMap<>();
    private final Map<String, ActiveTransfer> activeTransfers = new ConcurrentHashMap<>();
    private final Timeline poll;
    private final Timeline presencePoll;
    private final Timeline voiceDurationPoll;
    private HBox normalInputRow;
    private HBox voiceControls;
    private BorderPane attachmentComposer;
    private StackPane composerArea;
    private final List<Path> pendingAttachments = new ArrayList<>();
    private ContactStore.Contact selected;
    private ContactStore.Contact activeTransferContact;
    private java.util.concurrent.atomic.AtomicBoolean activeFileCancellation;
    private VoiceRecorder voiceRecorder;
    private ContactStore.Contact recordingContact;
    private long voiceRecordingStarted;
    private long voiceAccumulatedNanos;
    private boolean voicePaused;
    private boolean voiceDiscardRequested;
    private boolean sendAfterRecording;
    private Path completedVoiceRecording;
    private Clip activeVoiceClip;
    private Path activeVoicePath;
    private boolean activeVoiceTransfer;
    private boolean sending;

    private boolean loaded;

    MessengerPane(ContactManager manager) {
        this.manager = manager;

        contactList.setPlaceholder(new Label("No saved contacts yet."));
        contactList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ContactStore.Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                if (empty || contact == null) { setText(null); setGraphic(null); setTooltip(null); return; }
                Presence state = presence.getOrDefault(contact.link(), Presence.checking());
                Label dot = new Label("●");
                dot.getStyleClass().addAll("presence-dot", state.online() ? "online" : state.checkedAt() == 0 ? "checking" : "offline");
                Label name = new Label(contact.username());
                name.getStyleClass().add("contact-name");
                HBox heading = new HBox(6, dot, name);
                Label address = new Label(contact.onion());
                address.getStyleClass().add("contact-address");
                VBox cell = new VBox(2, heading, address);
                setText(null); setGraphic(cell);
                String message = state.checkedAt() == 0 ? "Checking availability through Tor…"
                        : (state.online() ? "Online" : "Offline or temporarily unreachable")
                        + " · checked " + TIME.format(Instant.ofEpochMilli(state.checkedAt()));
                dot.setTooltip(new Tooltip(message));
                name.setTooltip(new Tooltip(message));
            }
        });
        contactList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            selected = value;
            if (!pendingAttachments.isEmpty() && old != null && value != null && !old.link().equals(value.link()))
                clearAttachmentComposer();
            transcript.getItems().clear();
            setTransferControls(false);
            updateConversationHeader();
            refreshMessages();
        });

        transcript.setPlaceholder(new Label("No messages yet."));
        transcript.getStyleClass().add("chat-transcript");
        transcript.setCellFactory(list -> new MessageCell());
        conversationTitle.getStyleClass().add("conversation-title");
        conversationPresence.getStyleClass().add("conversation-presence");
        HBox conversationHeader = new HBox(10, conversationTitle, conversationPresence);
        conversationHeader.getStyleClass().add("conversation-header");
        composer.setPromptText("Write a message…");
        composer.getStyleClass().add("chat-composer");
        composer.textProperty().addListener((obs, oldText, newText) -> updateComposerMode());
        attachButton.setTooltip(new Tooltip("Attach file"));
        attachButton.getStyleClass().add("attach-icon-button");
        voiceButton.setTooltip(new Tooltip("Record a voice message (up to 30 seconds)"));
        voiceButton.getStyleClass().add("voice-icon-button");
        voiceButton.setGraphic(microphoneGlyph());
        sendButton.getStyleClass().add("send-icon-button");
        sendButton.setGraphic(envelopeGlyph());
        sendButton.setTooltip(new Tooltip("Send message"));
        attachButton.getStyleClass().add("secondary-button");
        composer.setOnAction(event -> send());
        sendButton.setOnAction(event -> send());
        attachButton.setOnAction(event -> chooseFile());
        voiceButton.setOnAction(event -> toggleVoiceRecording());
        discardVoiceButton.setGraphic(trashGlyph());
        discardVoiceButton.setTooltip(new Tooltip("Discard voice message"));
        discardVoiceButton.getStyleClass().add("voice-trash-button");
        discardVoiceButton.setOnAction(event -> discardVoiceMessage());
        pauseVoiceButton.setGraphic(pauseGlyph());
        pauseVoiceButton.setTooltip(new Tooltip("Pause recording"));
        pauseVoiceButton.getStyleClass().add("voice-control-button");
        pauseVoiceButton.setOnAction(event -> toggleVoicePause());
        voiceSendButton.setGraphic(sendGlyph());
        voiceSendButton.setTooltip(new Tooltip("Send voice message"));
        voiceSendButton.getStyleClass().add("send-icon-button");
        voiceSendButton.setOnAction(event -> sendVoiceMessage());
        attachmentCaption.setPromptText("Add a message to this file (optional)…");
        attachmentCaption.setWrapText(true);
        attachmentCaption.setPrefRowCount(2);
        attachmentCaption.getStyleClass().add("attachment-caption");
        attachmentCaption.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && event.isControlDown()) {
                event.consume();
                sendPendingAttachment();
            }
        });
        attachmentFilesList.getStyleClass().add("attachment-files-list");
        attachmentFilesViewport.setFitToHeight(true);
        attachmentFilesViewport.setFitToWidth(false);
        attachmentFilesViewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        attachmentFilesViewport.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        attachmentFilesViewport.setPannable(false);
        attachmentFilesViewport.getStyleClass().add("attachment-files-viewport");
        enableAttachmentDragScroll();
        attachmentAddButton.setGraphic(plusGlyph());
        attachmentAddButton.setTooltip(new Tooltip("Add files (up to 5 total, 1 GiB combined)"));
        attachmentAddButton.getStyleClass().add("attachment-add-button");
        attachmentAddButton.setOnAction(event -> addMoreAttachments());
        attachmentSendButton.setGraphic(sendGlyph());
        attachmentSendButton.setTooltip(new Tooltip("Send file and message"));
        attachmentSendButton.getStyleClass().add("send-icon-button");
        attachmentSendButton.setOnAction(event -> sendPendingAttachment());
        voiceDurationLabel.getStyleClass().add("voice-duration");
        voiceWaveform.getStyleClass().add("voice-waveform");
        setTransferControls(false);
        transferProgress.setMaxWidth(Double.MAX_VALUE);
        transferProgress.setVisible(false);
        transferProgress.setManaged(false);
        transferRate.setVisible(false);
        transferRate.setManaged(false);
        cancelFileButton.setTooltip(new Tooltip("Cancel file transfer"));
        cancelFileButton.setStyle("-fx-padding: 0 7 0 7; -fx-font-size: 15px; -fx-font-weight: bold;");
        cancelFileButton.setVisible(false);
        cancelFileButton.setManaged(false);
        cancelFileButton.setOnAction(event -> {
            cancelFileTransfer();
        });
        HBox transferRow = new HBox(8, transferProgress, transferRate, cancelFileButton);
        transferRow.getStyleClass().add("transfer-row");
        transferRate.getStyleClass().add("transfer-rate");
        HBox.setHgrow(transferProgress, Priority.ALWAYS);
        normalInputRow = new HBox(8, attachButton, composer, sendButton, voiceButton);
        normalInputRow.getStyleClass().add("chat-input-row");
        voiceControls = new HBox(12, discardVoiceButton, voiceDurationLabel, voiceWaveform, pauseVoiceButton, voiceSendButton);
        voiceControls.getStyleClass().add("voice-controls");
        voiceControls.setVisible(false);
        voiceControls.setManaged(false);
        HBox.setHgrow(voiceWaveform, Priority.ALWAYS);
        Region attachmentActionsSpacer = new Region();
        HBox.setHgrow(attachmentActionsSpacer, Priority.ALWAYS);
        HBox attachmentActions = new HBox(10, attachmentAddButton, attachmentActionsSpacer, attachmentSendButton);
        attachmentActions.getStyleClass().add("attachment-actions");
        attachmentComposer = new BorderPane();
        attachmentComposer.setTop(attachmentFilesViewport);
        attachmentComposer.setCenter(attachmentCaption);
        attachmentComposer.setBottom(attachmentActions);
        attachmentComposer.getStyleClass().add("attachment-composer");
        attachmentComposer.setMinHeight(250);
        attachmentComposer.setPrefHeight(300);
        attachmentComposer.setMaxHeight(Double.MAX_VALUE);
        BorderPane.setMargin(attachmentFilesViewport, new Insets(0, 0, 8, 0));
        BorderPane.setMargin(attachmentCaption, new Insets(2, 6, 4, 6));
        BorderPane.setMargin(attachmentActions, new Insets(8, 0, 0, 0));
        attachmentComposer.setVisible(false);
        attachmentComposer.setManaged(false);
        composerArea = new StackPane(normalInputRow, voiceControls, attachmentComposer);
        composerArea.getStyleClass().add("composer-area");
        HBox.setHgrow(composer, Priority.ALWAYS);
        updateComposerMode();

        Label securityNote = new Label("Messages and files are encrypted in transit. Files up to 1 GiB. Received files save in the app downloads folder; use Export… to choose another location. Local chat history and saved voice recordings are unencrypted.");
        securityNote.setWrapText(true);
        securityNote.getStyleClass().add("security-note");
        status.getStyleClass().add("chat-status");
        VBox conversation = new VBox(8, conversationHeader, transcript, status, composerArea, transferRow, securityNote);
        conversation.getStyleClass().add("conversation-pane");
        conversation.setPadding(new Insets(8));
        VBox.setVgrow(transcript, Priority.ALWAYS);
        VBox.setVgrow(composerArea, Priority.NEVER);
        setCenter(conversation);
        setLeft(contactList);
        getStyleClass().add("messenger-pane");
        contactList.getStyleClass().add("contact-list");
        setPadding(new Insets(4));
        BorderPane.setMargin(contactList, new Insets(4, 8, 4, 4));
        contactList.setPrefWidth(235);
        contactList.setMinWidth(185);

        poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshMessages()));
        poll.setCycleCount(Timeline.INDEFINITE);
        presencePoll = new Timeline(new KeyFrame(Duration.seconds(15), e -> checkContactsPresence()));
        presencePoll.setCycleCount(Timeline.INDEFINITE);
        voiceDurationPoll = new Timeline(new KeyFrame(Duration.millis(100), e -> updateVoiceDuration()));
        voiceDurationPoll.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) { poll.stop(); presencePoll.stop(); }
            else {
                if (!loaded) { loaded = true; loadContacts(); }
                poll.play();
                checkContactsPresence();
                presencePoll.play();
            }
        });
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
                    checkContactsPresence(contacts);
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load contacts: " + safe(e)));
            }
        });
    }

    private void checkContactsPresence() {
        checkContactsPresence(List.copyOf(contactList.getItems()));
    }

    private void checkContactsPresence(List<ContactStore.Contact> contacts) {
        long now = System.currentTimeMillis();
        for (ContactStore.Contact contact : contacts) {
            Presence previous = presence.get(contact.link());
            if (previous != null && now - previous.checkedAt() < TimeUnit.SECONDS.toMillis(30)) continue;
            if (!checkingPresence.add(contact.link())) continue;
            presence.putIfAbsent(contact.link(), Presence.checking());
            presenceWorkers.execute(() -> {
                boolean online = false;
                try { online = manager.isOnline(contact.link()); }
                catch (Exception ignored) { /* Unreachable and Tor errors both mean not reachable now. */ }
                boolean result = online;
                Presence oldState = presence.get(contact.link());
                Presence newState = new Presence(result, System.currentTimeMillis());
                presence.put(contact.link(), newState);
                checkingPresence.remove(contact.link());
                boolean stateChanged = oldState == null || oldState.checkedAt() == 0 || oldState.online() != result;
                if (stateChanged) Platform.runLater(() -> {
                    contactList.refresh();
                    if (selected != null && selected.link().equals(contact.link())) updateConversationHeader();
                });
            });
        }
    }

    private record Presence(boolean online, long checkedAt) {
        static Presence checking() { return new Presence(false, 0); }
    }

    private void updateConversationHeader() {
        ContactStore.Contact contact = selected;
        if (contact == null) {
            conversationTitle.setText("Select a contact");
            conversationPresence.setText("");
            return;
        }
        Presence state = presence.getOrDefault(contact.link(), Presence.checking());
        conversationTitle.setText(contact.username());
        conversationPresence.setText(state.checkedAt() == 0 ? "Checking via Tor…" : state.online() ? "Online" : "Offline or unreachable");
        conversationPresence.getStyleClass().removeAll("online", "offline", "checking");
        conversationPresence.getStyleClass().add(state.online() ? "online" : state.checkedAt() == 0 ? "checking" : "offline");
    }

    private void refreshMessages() {
        ContactStore.Contact contact = selected;
        if (contact == null || !loading.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("messenger-history").start(() -> {
            try {
                var messages = manager.messages(contact.link());
                Platform.runLater(() -> {
                    if (selected == null || !selected.link().equals(contact.link())) return;
                    List<MessageStore.Message> visibleMessages = new ArrayList<>(messages);
                    activeTransfers.values().stream()
                            .filter(transfer -> transfer.message.contactLink().equals(contact.link()))
                            .map(transfer -> transfer.message)
                            .forEach(visibleMessages::add);
                    visibleMessages.sort(java.util.Comparator.comparingLong(MessageStore.Message::timestamp));
                    if (!transcript.getItems().equals(visibleMessages)) {
                        boolean wasAtBottom = isTranscriptAtBottom();
                        List<MessageStore.Message> current = List.copyOf(transcript.getItems());
                        if (visibleMessages.size() >= current.size()
                                && visibleMessages.subList(0, current.size()).equals(current)) {
                            transcript.getItems().addAll(visibleMessages.subList(current.size(), visibleMessages.size()));
                        } else {
                            transcript.getItems().setAll(visibleMessages);
                        }
                        if (!visibleMessages.isEmpty() && wasAtBottom) transcript.scrollTo(visibleMessages.size() - 1);
                    }
                    sendButton.setDisable(sending);
                    if (!sending) status.setText("Paired with " + contact.username());
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load conversation: " + safe(e)));
            } finally { loading.set(false); }
        });
    }

    private boolean isTranscriptAtBottom() {
        if (transcript.getItems().isEmpty()) return true;
        for (Node node : transcript.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL)
                return bar.getMax() <= bar.getMin() || bar.getValue() >= bar.getMax() - 0.02;
        }
        return true;
    }

    private ActiveTransfer beginTransferBubble(ContactStore.Contact contact, Path path, String caption,
                                               AtomicBoolean cancellation) throws java.io.IOException {
        long size = Files.size(path);
        String id = "upload-" + java.util.UUID.randomUUID();
        MessageStore.Message message = new MessageStore.Message(id, contact.link(), true,
                caption == null ? "" : caption, path.getFileName().toString(), path.toAbsolutePath().toString(),
                size, System.currentTimeMillis());
        ActiveTransfer transfer = new ActiveTransfer(message, cancellation);
        activeTransfers.put(id, transfer);
        Platform.runLater(() -> {
            if (selected == null || !selected.link().equals(contact.link())) return;
            if (transcript.getItems().stream().noneMatch(existing -> existing.id().equals(id))) {
                transcript.getItems().add(message);
                transcript.scrollTo(transcript.getItems().size() - 1);
            }
        });
        return transfer;
    }

    private void finishTransferBubble(ActiveTransfer transfer) {
        if (transfer == null) return;
        activeTransfers.remove(transfer.message.id());
        Platform.runLater(this::refreshMessages);
    }

    private static final class ActiveTransfer {
        final MessageStore.Message message;
        final AtomicBoolean cancellation;
        final DoubleProperty progress = new SimpleDoubleProperty(0);
        final StringProperty speed = new SimpleStringProperty("0 B/s");

        ActiveTransfer(MessageStore.Message message, AtomicBoolean cancellation) {
            this.message = message;
            this.cancellation = cancellation;
        }
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
        chooser.setTitle("Choose up to 5 files to send (1 GiB combined maximum)");
        List<File> files = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        showAttachmentComposer(contact, files.stream().map(File::toPath).toList());
    }

    private void sendFile(ContactStore.Contact contact, Path path, boolean voiceMessage) {
        sendFile(contact, path, voiceMessage, "");
    }

    private void sendAttachments(ContactStore.Contact contact, List<Path> files, String caption) {
        final long totalBytes;
        try { totalBytes = totalAttachmentBytes(files); }
        catch (Exception e) { status.setText("Could not read selected files: " + safe(e)); return; }
        sending = true;
        activeTransferContact = contact;
        activeVoiceTransfer = false;
        var cancellation = new AtomicBoolean();
        activeFileCancellation = cancellation;
        setTransferControls(true);
        transferProgress.setManaged(false);
        transferProgress.setVisible(false);
        transferRate.setManaged(false);
        transferRate.setVisible(false);
        cancelFileButton.setTooltip(new Tooltip("Cancel file batch"));
        cancelFileButton.setDisable(false);
        cancelFileButton.setManaged(false);
        cancelFileButton.setVisible(false);
        status.setText("Sending " + files.size() + " files…");
        Thread.ofPlatform().daemon().name("messenger-file-batch").start(() -> {
            int sentCount = 0;
            long sentBytes = 0;
            ActiveTransfer currentBubble = null;
            SpeedMeter speed = new SpeedMeter();
            try {
                for (int index = 0; index < files.size(); index++) {
                    Path path = files.get(index);
                    long fileSize = Files.size(path);
                    currentBubble = beginTransferBubble(contact, path, index == 0 ? caption : "", cancellation);
                    ActiveTransfer bubble = currentBubble;
                    int completedFiles = sentCount;
                    long completedBytes = sentBytes;
                    Platform.runLater(() -> status.setText("Sending file " + (completedFiles + 1) + " of " + files.size()
                            + ": " + path.getFileName() + "…"));
                    manager.sendFile(contact.link(), path, index == 0 ? caption : "", cancellation,
                            transferred -> Platform.runLater(() -> {
                                double fileFraction = fileSize == 0 ? 1 : (double) transferred / fileSize;
                                bubble.progress.set(fileFraction);
                                bubble.speed.set(formatSpeed(speed.update(completedBytes + transferred, totalBytes, System.nanoTime())));
                            }),
                            message -> Platform.runLater(() -> {
                                if (sending && selected != null && selected.link().equals(contact.link())) status.setText(message);
                            }));
                    finishTransferBubble(currentBubble);
                    currentBubble = null;
                    sentCount++;
                    sentBytes += fileSize;
                    int completed = sentCount;
                    Platform.runLater(() -> status.setText("Sent " + completed + " of " + files.size() + " files…"));
                }
                int sent = sentCount;
                Platform.runLater(() -> {
                    finishBatchTransfer();
                    clearAttachmentComposer();
                    status.setText("Delivered " + sent + " files to " + contact.username() + ". Average speed: "
                            + formatSpeed(speed.average()) + ".");
                    refreshMessages();
                });
            } catch (Exception e) {
                finishTransferBubble(currentBubble);
                int sent = sentCount;
                Platform.runLater(() -> {
                    finishBatchTransfer();
                    if (cancellation.get()) status.setText("Transfer cancelled after " + sent + " of " + files.size() + " files.");
                    else status.setText("Transfer stopped after " + sent + " of " + files.size() + " files: " + safe(e));
                    refreshMessages();
                });
            }
        });
    }

    private void finishBatchTransfer() {
        sending = false;
        activeFileCancellation = null;
        activeTransferContact = null;
        activeVoiceTransfer = false;
        setTransferControls(false);
        transferProgress.setManaged(false);
        transferProgress.setVisible(false);
        transferRate.setManaged(false);
        transferRate.setVisible(false);
        cancelFileButton.setManaged(false);
        cancelFileButton.setVisible(false);
    }

    private void sendFile(ContactStore.Contact contact, Path path, boolean voiceMessage, String caption) {
        sending = true;
        activeTransferContact = contact;
        var cancellation = new java.util.concurrent.atomic.AtomicBoolean();
        activeFileCancellation = cancellation;
        activeVoiceTransfer = voiceMessage;
        cancelFileButton.setTooltip(new Tooltip(voiceMessage ? "Cancel voice message" : "Cancel file transfer"));
        setTransferControls(true);
        transferProgress.setManaged(false);
        transferProgress.setVisible(false);
        transferRate.setManaged(false);
        transferRate.setVisible(false);
        cancelFileButton.setDisable(false);
        cancelFileButton.setManaged(false);
        cancelFileButton.setVisible(false);
        if (voiceMessage) {
            discardVoiceButton.setDisable(false);
            pauseVoiceButton.setDisable(true);
            voiceSendButton.setDisable(true);
        }
        status.setText(voiceMessage ? "Sending voice message…" : "Sending " + path.getFileName() + "…");
        Thread.ofPlatform().daemon().name("messenger-file-send").start(() -> {
            ActiveTransfer bubble = null;
            try {
                long size = Files.size(path);
                bubble = beginTransferBubble(contact, path, caption, cancellation);
                ActiveTransfer activeBubble = bubble;
                SpeedMeter speed = new SpeedMeter();
                manager.sendFile(contact.link(), path, caption, cancellation, transferred -> Platform.runLater(() -> {
                            activeBubble.progress.set(size == 0 ? 1.0 : (double) transferred / size);
                            activeBubble.speed.set(formatSpeed(speed.update(transferred, size, System.nanoTime())));
                        }),
                        message -> Platform.runLater(() -> {
                            if (sending && selected != null && selected.link().equals(contact.link())) status.setText(message);
                        }));
                finishTransferBubble(bubble);
                bubble = null;
                Platform.runLater(() -> {
                    sending = false;
                    activeFileCancellation = null;
                    activeTransferContact = null;
                    activeVoiceTransfer = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    transferRate.setManaged(false);
                    transferRate.setVisible(false);
                    cancelFileButton.setManaged(false);
                    cancelFileButton.setVisible(false);
                    status.setText(cancellation.get() ? (voiceMessage ? "Voice message cancelled." : "File transfer cancelled.")
                            : (voiceMessage ? "Voice message delivered." : "File delivered and saved by " + contact.username())
                            + " Average speed: " + formatSpeed(speed.average()) + ".");
                    if (voiceMessage) resetVoiceComposer(cancellation.get() ? "Voice message cancelled." : status.getText());
                    else if (!cancellation.get()) clearAttachmentComposer();
                    refreshMessages();
                });
            } catch (Exception e) {
                finishTransferBubble(bubble);
                if (voiceMessage) try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    sending = false;
                    activeFileCancellation = null;
                    activeTransferContact = null;
                    activeVoiceTransfer = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    transferRate.setManaged(false);
                    transferRate.setVisible(false);
                    cancelFileButton.setManaged(false);
                    cancelFileButton.setVisible(false);
                    status.setText(cancellation.get() ? (voiceMessage ? "Voice message cancelled." : "File transfer cancelled.")
                            : (voiceMessage ? "Voice message was not delivered: " : "File was not delivered: ") + safe(e));
                    if (voiceMessage) resetVoiceComposer(status.getText());
                    else attachmentSendButton.setDisable(false);
                });
            }
        });
    }

    private void showAttachmentComposer(ContactStore.Contact contact, List<Path> files) {
        if (selected == null || !selected.link().equals(contact.link())) return;
        try {
            var combined = new ArrayList<>(pendingAttachments);
            for (Path path : files) if (!combined.contains(path)) combined.add(path);
            if (combined.size() > 5) {
                status.setText("You can attach up to 5 files at a time.");
                return;
            }
            long total = totalAttachmentBytes(combined);
            if (total > ContactMessaging.MAX_FILE_BYTES) {
                status.setText("The combined size of attachments must be 1 GiB or less.");
                return;
            }
            boolean firstSelection = pendingAttachments.isEmpty();
            pendingAttachments.clear();
            pendingAttachments.addAll(combined);
            renderAttachmentRows();
            if (firstSelection) attachmentCaption.clear();
            normalInputRow.setVisible(false);
            normalInputRow.setManaged(false);
            voiceControls.setVisible(false);
            voiceControls.setManaged(false);
            attachmentComposer.setVisible(true);
            attachmentComposer.setManaged(true);
            VBox.setVgrow(transcript, Priority.SOMETIMES);
            VBox.setVgrow(composerArea, Priority.ALWAYS);
            attachmentSendButton.setDisable(false);
            attachmentCaption.setDisable(false);
            status.setText(pendingAttachments.size() + " file" + (pendingAttachments.size() == 1 ? "" : "s")
                    + " ready · " + formatBytes(total) + " total. Add an optional message, then send.");
            if (firstSelection) attachmentCaption.requestFocus();
        } catch (Exception e) {
            status.setText("Could not open the selected file: " + safe(e));
        }
    }

    private void addMoreAttachments() {
        if (sending || selected == null) return;
        if (pendingAttachments.size() >= 5) {
            status.setText("You can attach up to 5 files at a time.");
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("Add files (up to 5 total, 1 GiB combined)");
        List<File> files = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (files != null && !files.isEmpty())
            showAttachmentComposer(selected, files.stream().map(File::toPath).toList());
    }

    private void renderAttachmentRows() {
        attachmentFilesList.getChildren().clear();
        for (Path path : pendingAttachments) {
            try {
                Label typeIcon = new Label(fileTypeLabel(path));
                typeIcon.getStyleClass().addAll("attachment-type-icon", "compact", fileTypeClass(path));
                Label name = new Label(path.getFileName().toString());
                name.setWrapText(true);
                name.setMaxWidth(Double.MAX_VALUE);
                name.setTooltip(new Tooltip(path.getFileName().toString()));
                name.getStyleClass().add("attachment-name");
                Label size = new Label(formatBytes(Files.size(path)));
                size.getStyleClass().add("attachment-size");
                VBox details = new VBox(2, name, size);
                HBox.setHgrow(details, Priority.ALWAYS);
                Button remove = new Button("×");
                remove.getStyleClass().add("attachment-remove-button");
                remove.setMinSize(28, 28);
                remove.setPrefSize(28, 28);
                remove.setMaxSize(28, 28);
                remove.setPadding(Insets.EMPTY);
                remove.setTooltip(new Tooltip("Remove " + path.getFileName()));
                remove.setOnAction(event -> removeAttachment(path));
                HBox row = new HBox(10, typeIcon, details, remove);
                row.getStyleClass().add("attachment-file-row");
                HBox.setHgrow(typeIcon, Priority.NEVER);
                HBox.setHgrow(remove, Priority.NEVER);
                HBox.setHgrow(details, Priority.ALWAYS);
                remove.setMinWidth(28);
                remove.setPrefWidth(28);
                remove.setMaxWidth(28);
                name.setMinWidth(0);
                attachmentFilesList.getChildren().add(row);
            } catch (Exception e) {
                status.setText("Could not read an attachment: " + safe(e));
            }
        }
    }

    private void enableAttachmentDragScroll() {
        final double[] dragStart = new double[2];
        final boolean[] dragging = new boolean[1];
        final boolean[] suppressClick = new boolean[1];
        attachmentFilesViewport.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            dragStart[0] = event.getSceneX();
            dragStart[1] = attachmentFilesViewport.getHvalue();
            dragging[0] = false;
        });
        attachmentFilesViewport.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown()) return;
            double delta = event.getSceneX() - dragStart[0];
            if (Math.abs(delta) > 3 && !dragging[0]) {
                dragging[0] = true;
                suppressClick[0] = true;
                attachmentFilesViewport.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dragging"), true);
            }
            double range = attachmentFilesList.getWidth() - attachmentFilesViewport.getViewportBounds().getWidth();
            if (dragging[0] && range > 0) {
                attachmentFilesViewport.setHvalue(clamp(dragStart[1] - delta / range));
                event.consume();
            }
        });
        attachmentFilesViewport.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (dragging[0]) {
                attachmentFilesViewport.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dragging"), false);
                dragging[0] = false;
            }
        });
        attachmentFilesViewport.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (suppressClick[0]) {
                event.consume();
                suppressClick[0] = false;
            }
        });
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void removeAttachment(Path path) {
        pendingAttachments.remove(path);
        if (pendingAttachments.isEmpty()) {
            clearAttachmentComposer();
            return;
        }
        renderAttachmentRows();
        try { status.setText(pendingAttachments.size() + " files · " + formatBytes(totalAttachmentBytes(pendingAttachments)) + " total."); }
        catch (Exception e) { status.setText("Could not read attachment sizes: " + safe(e)); }
    }

    private static long totalAttachmentBytes(List<Path> files) throws java.io.IOException {
        long total = 0;
        for (Path file : files) {
            long size = Files.size(file);
            if (size > ContactMessaging.MAX_FILE_BYTES - total) return ContactMessaging.MAX_FILE_BYTES + 1;
            total += size;
        }
        return total;
    }

    private void sendPendingAttachment() {
        if (sending || pendingAttachments.isEmpty() || selected == null) return;
        String caption = attachmentCaption.getText() == null ? "" : attachmentCaption.getText().strip();
        if (caption.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            status.setText("Attachment message must be 4096 UTF-8 bytes or fewer.");
            return;
        }
        var files = List.copyOf(pendingAttachments);
        clearAttachmentComposer();
        sendAttachments(selected, files, caption);
    }

    private void clearAttachmentComposer() {
        pendingAttachments.clear();
        attachmentFilesList.getChildren().clear();
        attachmentCaption.clear();
        attachmentCaption.setDisable(false);
        attachmentAddButton.setDisable(false);
        attachmentComposer.setVisible(false);
        attachmentComposer.setManaged(false);
        VBox.setVgrow(transcript, Priority.ALWAYS);
        VBox.setVgrow(composerArea, Priority.NEVER);
        normalInputRow.setVisible(true);
        normalInputRow.setManaged(true);
        updateComposerMode();
    }

    private static String fileTypeLabel(Path path) {
        String ext = extension(path);
        if (List.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic").contains(ext)) return "IMG";
        if (List.of("mp4", "mov", "mkv", "avi", "webm").contains(ext)) return "VID";
        if (List.of("mp3", "wav", "m4a", "ogg", "flac", "aac").contains(ext)) return "AUD";
        if (List.of("zip", "7z", "rar", "tar", "gz").contains(ext)) return "ZIP";
        if (ext.equals("pdf")) return "PDF";
        if (List.of("doc", "docx", "txt", "md", "rtf").contains(ext)) return "DOC";
        return ext.isBlank() ? "FILE" : ext.substring(0, Math.min(ext.length(), 4)).toUpperCase(java.util.Locale.ROOT);
    }

    private static String fileTypeClass(Path path) {
        return switch (fileTypeLabel(path)) {
            case "IMG" -> "image";
            case "VID" -> "video";
            case "AUD" -> "audio";
            case "ZIP" -> "archive";
            case "PDF" -> "pdf";
            case "DOC" -> "document";
            default -> "generic";
        };
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private void toggleVoiceRecording() {
        if (sending || voiceRecorder != null) return;
        ContactStore.Contact contact = selected;
        if (contact == null) {
            status.setText("Select a saved contact before recording a voice message.");
            return;
        }
        Path directory = AppData.directory().resolve("voice-messages");
        Path output = directory.resolve("voice-message-" + java.util.UUID.randomUUID() + ".wav");
        try { Files.createDirectories(directory); }
        catch (Exception e) { status.setText("Could not prepare voice recording: " + safe(e)); return; }

        sending = true;
        setTransferControls(true);
        showVoiceControls(true);
        completedVoiceRecording = null;
        voicePaused = false;
        voiceDiscardRequested = false;
        voiceAccumulatedNanos = 0;
        voiceWaveform.clear();
        voiceDurationLabel.setText("0:00");
        discardVoiceButton.setDisable(false);
        pauseVoiceButton.setDisable(true);
        voiceSendButton.setDisable(true);
        status.setText("Preparing microphone…");
        recordingContact = contact;
        VoiceRecorder recorder = new VoiceRecorder(output);
        voiceRecorder = recorder;
        recorder.start(
                () -> Platform.runLater(() -> {
                    if (voiceRecorder != recorder) return;
                    voiceRecordingStarted = System.nanoTime();
                    pauseVoiceButton.setDisable(false);
                    voiceSendButton.setDisable(false);
                    status.setText("Recording voice message… 0:00 / 0:30");
                    voiceDurationPoll.playFromStart();
                }),
                level -> Platform.runLater(() -> voiceWaveform.addLevel(level)),
                recorded -> {
                    final long recordedBytes;
                    try { recordedBytes = Files.size(recorded); }
                    catch (Exception e) {
                        Platform.runLater(() -> {
                            voiceRecorder = null;
                            recordingContact = null;
                            try { Files.deleteIfExists(recorded); } catch (Exception ignored) {}
                            resetVoiceComposer("Could not prepare voice message: " + safe(e));
                        });
                        return;
                    }
                    Platform.runLater(() -> {
                        if (voiceRecorder != recorder) { try { Files.deleteIfExists(recorded); } catch (Exception ignored) {} return; }
                        voiceDurationPoll.stop();
                        voiceRecorder = null;
                        if (voiceDiscardRequested) {
                            try { Files.deleteIfExists(recorded); } catch (Exception ignored) {}
                            resetVoiceComposer("Voice recording discarded.");
                            return;
                        }
                        if (recordedBytes <= 44) {
                            try { Files.deleteIfExists(recorded); } catch (Exception ignored) {}
                            resetVoiceComposer("No audio was recorded. Check microphone access and try again.");
                        } else if (sendAfterRecording) {
                            completedVoiceRecording = recorded;
                            sendAfterRecording = false;
                            sendVoiceMessage();
                        } else {
                            completedVoiceRecording = recorded;
                            pauseVoiceButton.setDisable(true);
                            voiceSendButton.setDisable(false);
                            status.setText("Voice message ready to send.");
                        }
                    });
                },
                error -> Platform.runLater(() -> {
                    if (voiceRecorder != recorder) return;
                    voiceDurationPoll.stop();
                    voiceRecorder = null;
                    recordingContact = null;
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    resetVoiceComposer("Could not record audio: " + safe(error));
                }),
                () -> Platform.runLater(() -> {
                    if (voiceRecorder != recorder) return;
                    voiceDurationPoll.stop();
                    voiceRecorder = null;
                    recordingContact = null;
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    resetVoiceComposer("Voice recording discarded.");
                }));
    }

    private void updateVoiceDuration() {
        if (voiceRecorder == null) { voiceDurationPoll.stop(); return; }
        long elapsedNanos = voiceAccumulatedNanos + (voicePaused ? 0 : System.nanoTime() - voiceRecordingStarted);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;
        voiceDurationLabel.setText(String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds));
        status.setText(String.format(java.util.Locale.ROOT, "%s voice message… %d:%02d / 0:30",
                voicePaused ? "Paused" : "Recording", minutes, seconds));
        if (elapsedSeconds >= 30) finishVoiceRecording(false);
    }

    private void finishVoiceRecording(boolean sendWhenReady) {
        VoiceRecorder recorder = voiceRecorder;
        if (recorder == null) return;
        sendAfterRecording = sendWhenReady;
        voiceDurationPoll.stop();
        pauseVoiceButton.setDisable(true);
        voiceSendButton.setDisable(true);
        status.setText("Preparing voice message…");
        recorder.stop();
    }

    private void toggleVoicePause() {
        VoiceRecorder recorder = voiceRecorder;
        if (recorder == null || completedVoiceRecording != null) return;
        if (voicePaused) {
            voiceRecordingStarted = System.nanoTime();
            voicePaused = false;
            recorder.resume();
            pauseVoiceButton.setGraphic(pauseGlyph());
            pauseVoiceButton.setTooltip(new Tooltip("Pause recording"));
        } else {
            voiceAccumulatedNanos += System.nanoTime() - voiceRecordingStarted;
            voicePaused = true;
            recorder.pause();
            pauseVoiceButton.setGraphic(playGlyph());
            pauseVoiceButton.setTooltip(new Tooltip("Resume recording"));
        }
        updateVoiceDuration();
    }

    private void sendVoiceMessage() {
        if (voiceRecorder != null) { finishVoiceRecording(true); return; }
        if (completedVoiceRecording == null || activeVoiceTransfer) return;
        Path recording = completedVoiceRecording;
        ContactStore.Contact contact = recordingContact;
        if (contact == null) { status.setText("Choose a contact before sending the voice message."); return; }
        voiceSendButton.setDisable(true);
        sendFile(contact, recording, true);
    }

    private void discardVoiceMessage() {
        if (voiceRecorder != null) { cancelVoiceRecording(); return; }
        if (activeVoiceTransfer) { cancelFileTransfer(); return; }
        Path recording = completedVoiceRecording;
        if (recording != null) try { Files.deleteIfExists(recording); } catch (Exception ignored) {}
        completedVoiceRecording = null;
        recordingContact = null;
        resetVoiceComposer("Voice message discarded.");
    }

    private void resetVoiceComposer(String message) {
        voiceDurationPoll.stop();
        completedVoiceRecording = null;
        recordingContact = null;
        sendAfterRecording = false;
        sending = false;
        setTransferControls(false);
        transferProgress.setManaged(false);
        transferProgress.setVisible(false);
        transferRate.setManaged(false);
        transferRate.setVisible(false);
        cancelFileButton.setManaged(false);
        cancelFileButton.setVisible(false);
        voiceWaveform.clear();
        showVoiceControls(false);
        status.setText(message);
    }

    private void showVoiceControls(boolean visible) {
        voiceControls.setVisible(visible);
        voiceControls.setManaged(visible);
        normalInputRow.setVisible(!visible);
        normalInputRow.setManaged(!visible);
    }

    private void cancelVoiceRecording() {
        VoiceRecorder recorder = voiceRecorder;
        if (recorder == null) return;
        voiceDiscardRequested = true;
        discardVoiceButton.setDisable(true);
        status.setText("Discarding voice recording…");
        recorder.cancel();
    }

    private void cancelFileTransfer() {
        var cancellation = activeFileCancellation;
        ContactStore.Contact contact = activeTransferContact;
        if (cancellation == null || contact == null || !cancellation.compareAndSet(false, true)) return;
        if (activeVoiceTransfer) discardVoiceButton.setDisable(true);
        else cancelFileButton.setDisable(true);
        status.setText(activeVoiceTransfer ? "Cancelling voice message…" : "Cancelling file transfer…");
        manager.cancelTransfer(contact.link());
    }

    private void export(MessageStore.Message message) {
        if (sending || message.attachmentPath() == null) return;
        Path source = Path.of(message.attachmentPath());
        var chooser = new FileChooser();
        chooser.setTitle("Export received attachment");
        chooser.setInitialFileName(message.attachmentName());
        File chosen = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
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
        transferRate.setText("0 B/s");
        transferRate.setManaged(true);
        transferRate.setVisible(true);
        status.setText("Exporting " + message.attachmentName() + "…");
        Thread.ofPlatform().daemon().name("messenger-file-export").start(() -> {
            Path temporary = null;
            try {
                Path parent = destination.getParent();
                if (parent == null || !Files.isDirectory(parent)) throw new java.io.IOException("Choose an existing folder.");
                temporary = Files.createTempFile(parent, ".onion-export-", ".part");
                long copied = 0;
                long lastReported = 0;
                long lastReportedAt = System.nanoTime();
                SpeedMeter speed = new SpeedMeter();
                speed.update(0, message.attachmentSize(), lastReportedAt);
                try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        copied += count;
                        long now = System.nanoTime();
                        if (copied - lastReported >= 256 * 1024 || now - lastReportedAt >= 250_000_000L || copied == message.attachmentSize()) {
                            long total = copied;
                            double currentSpeed = speed.update(total, message.attachmentSize(), now);
                            Platform.runLater(() -> {
                                transferProgress.setProgress(message.attachmentSize() == 0
                                        ? 1 : (double) total / message.attachmentSize());
                                transferRate.setText(formatSpeed(currentSpeed));
                            });
                            lastReported = copied;
                            lastReportedAt = now;
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
                    transferRate.setManaged(false);
                    transferRate.setVisible(false);
                    status.setText("Exported to " + destination + ". Average speed: " + formatSpeed(speed.average()) + ".");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sending = false;
                    setTransferControls(false);
                    transferProgress.setManaged(false);
                    transferProgress.setVisible(false);
                    transferRate.setManaged(false);
                    transferRate.setVisible(false);
                    status.setText("Could not export the file: " + safe(e));
                });
            } finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            }
        });
    }

    private void setTransferControls(boolean busy) {
        sendButton.setDisable(busy || selected == null || composer.getText().isBlank());
        attachButton.setDisable(busy || selected == null);
        voiceButton.setDisable(busy || selected == null);
        composer.setDisable(busy || selected == null);
        attachmentCaption.setDisable(busy);
        attachmentSendButton.setDisable(busy || pendingAttachments.isEmpty() || selected == null);
        attachmentAddButton.setDisable(busy || pendingAttachments.size() >= 5 || selected == null);
    }

    private void updateComposerMode() {
        boolean hasText = !composer.getText().isBlank();
        sendButton.setVisible(hasText);
        sendButton.setManaged(hasText);
        voiceButton.setVisible(!hasText);
        voiceButton.setManaged(!hasText);
        sendButton.setDisable(sending || selected == null || !hasText);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) bytesPerSecond = 0;
        if (bytesPerSecond < 1_000) return String.format(java.util.Locale.ROOT, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1_000_000) return String.format(java.util.Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1_000);
        if (bytesPerSecond < 1_000_000_000) return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1_000_000);
        return String.format(java.util.Locale.ROOT, "%.1f GB/s", bytesPerSecond / 1_000_000_000);
    }

    private static SVGPath microphoneGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.93V21h2v-3.07A7 7 0 0 0 19 11z");
        icon.getStyleClass().add("mic-glyph");
        return icon;
    }

    private static SVGPath plusGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M11 2h2v9h9v2h-9v9h-2v-9H2v-2h9z");
        icon.getStyleClass().add("plus-glyph");
        return icon;
    }

    private static SVGPath stopGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M6 6h12v12H6z");
        icon.getStyleClass().add("mic-glyph");
        return icon;
    }

    private static SVGPath envelopeGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M3 3l18 9-18 9v-7l12-2L3 10V3z");
        icon.getStyleClass().add("send-glyph");
        return icon;
    }

    private static SVGPath sendGlyph() { return envelopeGlyph(); }

    private static SVGPath trashGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M4 7h16 M10 4h4 M6 7l1 14h10l1-14 M10 10v8 M14 10v8");
        icon.getStyleClass().add("trash-glyph");
        return icon;
    }

    private static SVGPath pauseGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M7 5h4v14H7z M13 5h4v14h-4z");
        icon.getStyleClass().add("voice-control-glyph");
        return icon;
    }

    private static SVGPath playGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M7 4l13 8-13 8z");
        icon.getStyleClass().add("voice-control-glyph");
        return icon;
    }

    /** Rolling three-second rate during transfer, plus an average over the actual data-copy interval. */
    private static final class SpeedMeter {
        private record Sample(long bytes, long time) {}
        private final Deque<Sample> samples = new ArrayDeque<>();
        private long startedAt;
        private long completedAt;
        private long finalBytes;

        double update(long bytes, long total, long now) {
            if (startedAt == 0) startedAt = now;
            samples.addLast(new Sample(bytes, now));
            long cutoff = now - TimeUnit.SECONDS.toNanos(3);
            while (samples.size() > 2) {
                var second = samples.stream().skip(1).findFirst().orElseThrow();
                if (second.time() >= cutoff) break;
                samples.removeFirst();
            }
            if (total == 0 || bytes >= total) {
                completedAt = now;
                finalBytes = bytes;
            }
            Sample first = samples.getFirst();
            Sample last = samples.getLast();
            long elapsed = last.time() - first.time();
            return elapsed <= 0 ? 0 : (last.bytes() - first.bytes()) * 1_000_000_000.0 / elapsed;
        }

        double average() {
            long elapsed = completedAt - startedAt;
            return elapsed <= 0 ? 0 : finalBytes * 1_000_000_000.0 / elapsed;
        }
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
            heading.getStyleClass().add("message-heading");
            Label content;
            if (message.attachmentName() != null) {
                boolean voice = isVoiceMessage(message);
                content = new Label(voice ? "🎙 Voice message" : "📎 " + message.attachmentName() + " (" + formatBytes(message.attachmentSize()) + ")");
                content.setWrapText(true);
                content.getStyleClass().add("message-content");
                VBox rows = new VBox(5, heading, content);
                rows.getStyleClass().addAll("message-card", message.outgoing() ? "outgoing" : "incoming");
                if (message.body() != null && !message.body().isBlank()) {
                    Label caption = new Label(message.body());
                    caption.setWrapText(true);
                    caption.getStyleClass().add("message-content");
                    rows.getChildren().add(caption);
                }
                ActiveTransfer transfer = activeTransfers.get(message.id());
                if (transfer != null) {
                    ProgressBar progress = new ProgressBar();
                    progress.setMinWidth(110);
                    progress.setMaxWidth(Double.MAX_VALUE);
                    progress.progressProperty().bind(transfer.progress);
                    Label percent = new Label();
                    percent.textProperty().bind(Bindings.createStringBinding(
                            () -> String.format(java.util.Locale.ROOT, "%.0f%%", transfer.progress.get() * 100), transfer.progress));
                    percent.getStyleClass().add("message-transfer-rate");
                    Label rate = new Label();
                    rate.textProperty().bind(transfer.speed);
                    rate.getStyleClass().add("message-transfer-rate");
                    Button cancel = new Button("×");
                    cancel.getStyleClass().add("message-transfer-cancel");
                    cancel.setTooltip(new Tooltip("Cancel this file transfer"));
                    cancel.setOnAction(event -> {
                        if (transfer.cancellation.compareAndSet(false, true)) {
                            cancel.setDisable(true);
                            cancel.setText("…");
                            status.setText("Cancelling " + message.attachmentName() + "…");
                            manager.cancelTransfer(message.contactLink());
                        }
                    });
                    HBox progressRow = new HBox(8, progress, percent, rate, cancel);
                    progressRow.getStyleClass().add("message-transfer-row");
                    HBox.setHgrow(progress, Priority.ALWAYS);
                    rows.getChildren().add(progressRow);
                }
                HBox actions = new HBox(8);
                if (voice) {
                    Button play = new Button(isPlaying(message.attachmentPath()) ? "■ Stop" : "▶ Play");
                    play.getStyleClass().add("voice-play-button");
                    if (message.attachmentPath() == null || !Files.isRegularFile(Path.of(message.attachmentPath()))) {
                        play.setDisable(true);
                        play.setTooltip(new Tooltip("Voice recording is unavailable on this device."));
                    } else play.setOnAction(event -> playVoice(Path.of(message.attachmentPath())));
                    actions.getChildren().add(play);
                }
                if (!message.outgoing()) {
                    Button exportButton = new Button("Export…");
                    exportButton.getStyleClass().add("export-button");
                    exportButton.setOnAction(event -> export(message));
                    actions.getChildren().add(exportButton);
                }
                if (!actions.getChildren().isEmpty()) rows.getChildren().add(actions);
                setText(null); setGraphic(wrapMessageCard(rows, message.outgoing())); return;
            } else {
                content = new Label(message.body());
                content.setWrapText(true);
            }
            content.getStyleClass().add("message-content");
            VBox rows = new VBox(4, heading, content);
            rows.getStyleClass().addAll("message-card", message.outgoing() ? "outgoing" : "incoming");
            rows.setPadding(new Insets(6));
            setText(null); setGraphic(wrapMessageCard(rows, message.outgoing()));
        }

        private HBox wrapMessageCard(VBox card, boolean outgoing) {
            HBox alignment = new HBox(card);
            alignment.setAlignment(outgoing ? javafx.geometry.Pos.CENTER_RIGHT : javafx.geometry.Pos.CENTER_LEFT);
            alignment.setMaxWidth(Double.MAX_VALUE);
            alignment.prefWidthProperty().bind(transcript.widthProperty().subtract(34));
            card.setMaxWidth(680);
            return alignment;
        }
    }

    private static boolean isVoiceMessage(MessageStore.Message message) {
        return message.attachmentName() != null && message.attachmentName().startsWith("voice-message-")
                && message.attachmentName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
    }

    private boolean isPlaying(String path) {
        return path != null && activeVoiceClip != null && activeVoiceClip.isRunning() && Path.of(path).equals(activeVoicePath);
    }

    private void playVoice(Path path) {
        if (activeVoiceClip != null) {
            Clip previous = activeVoiceClip;
            boolean sameMessage = path.equals(activeVoicePath);
            activeVoiceClip = null;
            activeVoicePath = null;
            try { previous.stop(); previous.close(); } catch (Exception ignored) {}
            transcript.refresh();
            if (sameMessage) return;
        }
        status.setText("Loading voice message…");
        Thread.ofPlatform().daemon().name("voice-playback").start(() -> {
            Clip clip = null;
            try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
                clip = AudioSystem.getClip();
                clip.open(audio);
                Clip ready = clip;
                Platform.runLater(() -> {
                    activeVoiceClip = ready;
                    activeVoicePath = path;
                    ready.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP) Platform.runLater(() -> {
                            if (activeVoiceClip == ready) {
                                activeVoiceClip = null;
                                activeVoicePath = null;
                                ready.close();
                                transcript.refresh();
                            }
                        });
                    });
                    ready.start();
                    status.setText("Playing voice message…");
                    transcript.refresh();
                });
            } catch (Exception e) {
                if (clip != null) try { clip.close(); } catch (Exception ignored) {}
                Platform.runLater(() -> status.setText("Could not play voice message: " + safe(e)));
            }
        });
    }
}
