package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.account.AppData;
import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.contacts.ContactMessaging;
import kakha.kudava.onionp2p.contacts.ContactStore;
import kakha.kudava.onionp2p.messaging.MessageStore;
import kakha.kudava.onionp2p.drive.SharedDriveStore;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.beans.binding.NumberBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ObservableStringValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.StageStyle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static kakha.kudava.onionp2p.contacts.ContactMessaging.MAX_BODY_BYTES;

/** Embedded chat pane backed by saved contacts and local message history. */
final class MessengerPane extends BorderPane {
    private enum DriveSortColumn { NAME, TYPE, SIZE }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CHAT_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
    private static final long FLOATING_DATE_TIMEOUT = TimeUnit.MILLISECONDS.toNanos(900);
    private static final int COUNTER_THRESHOLD = MAX_BODY_BYTES - 512;
    private static final String REACTION_PREFIX = "\u001eONION_REACTION_V1:";
    private static volatile MessengerPane activePane;
    private final Label torIndicatorDot = new Label("●");
    private final ScaleTransition torStartupPulse = new ScaleTransition(Duration.seconds(0.65), torIndicatorDot);
    private final ContactManager manager;
    private final String localUsername;
    private final Runnable onAddContact;
    private Runnable showChatsAction;
    private final ListView<ContactStore.Contact> contactList = new ListView<>();
    private final ObservableList<ContactStore.Contact> allContacts = FXCollections.observableArrayList();
    private final FilteredList<ContactStore.Contact> filteredContacts = new FilteredList<>(allContacts, contact -> true);
    private final TextField contactSearch = new TextField();
    private final ListView<MessageStore.Message> transcript = new ListView<>();
    private final NumberBinding transcriptRowWidth = Bindings.max(0, transcript.widthProperty().subtract(50));
    private final NumberBinding textBubbleMaxWidth = Bindings.min(520, transcriptRowWidth.multiply(0.70));
    private final ObjectProperty<LocalDate> transcriptToday = new SimpleObjectProperty<>(LocalDate.now(TIME.getZone()));
    private final Label floatingDate = new Label();
    private long lastScrollInput;
    private final AnimationTimer floatingDateTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (System.nanoTime() - lastScrollInput >= FLOATING_DATE_TIMEOUT) hideFloatingDate();
            else updateFloatingDate();
        }
    };
    private final Label conversationAvatar = new Label();
    private final Label conversationTitle = new Label("Select a contact");
    private final Label conversationPresence = new Label();
    private final TextField composer = new TextField();
    private final IntegerBinding messageBytes = Bindings.createIntegerBinding(
            () -> utf8Bytes(composer.getText()), composer.textProperty());
    private final Button sendButton = new Button();
    private final Button attachButton = new Button("📎");
    private final Button voiceButton = new Button();
    private final Button discardVoiceButton = new Button();
    private final Button pauseVoiceButton = new Button();
    private final Button voiceSendButton = new Button();
    private final TextArea attachmentCaption = new TextArea();
    private final StringBinding captionDraft = Bindings.createStringBinding(() -> {
        String text = attachmentCaption.getText();
        return text == null ? "" : text.strip();
    }, attachmentCaption.textProperty());
    private final IntegerBinding captionBytes = Bindings.createIntegerBinding(
            () -> utf8Bytes(captionDraft.get()), captionDraft);
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
    private final Map<String, ActiveOutgoingMessage> activeOutgoingMessages = new ConcurrentHashMap<>();
    private final java.util.Set<String> readReceiptsSent = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> readReceiptAttempts = new ConcurrentHashMap<>();
    private Map<String, Map<String, ReactionCounts>> messageReactions = Map.of();
    private final Map<String, Map<String, Integer>> pendingMessageReactions = new HashMap<>();
    private final Timeline poll;
    private final Timeline presencePoll;
    private final Timeline seenPoll;
    private final Timeline voiceDurationPoll;
    private HBox normalInputRow;
    private HBox voiceControls;
    private BorderPane attachmentComposer;
    private StackPane composerArea;
    private final ObservableList<Path> pendingAttachments = FXCollections.observableArrayList();
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
    private final ObjectProperty<Path> activeVoicePath = new SimpleObjectProperty<>();
    private final BooleanProperty voicePlaying = new SimpleBooleanProperty();
    private final DoubleProperty voicePlaybackProgress = new SimpleDoubleProperty();
    private final Timeline voicePlaybackPoll = new Timeline(new KeyFrame(Duration.millis(60), event -> updateVoicePlayback()));
    private final Map<Path, CompletableFuture<VoiceNote>> voiceNotes = new HashMap<>();
    private final ExecutorService voiceNoteReader = Executors.newSingleThreadExecutor(runnable ->
            Thread.ofPlatform().daemon().name("voice-waveform").unstarted(runnable));
    private final ExecutorService driveWorkers = Executors.newSingleThreadExecutor(runnable ->
            Thread.ofPlatform().daemon().name("drive-import").unstarted(runnable));
    private DriveSortColumn driveSortColumn = DriveSortColumn.NAME;
    private boolean driveSortAscending = true;
    private int drivePageSize = 25;
    private int drivePageIndex;
    private long voicePlaybackRequest;
    private boolean activeVoiceTransfer;
    private boolean sending;
    private Runnable refreshSharedDrive;

    private boolean loaded;

    MessengerPane(ContactManager manager, String username, Runnable onAddContact) {
        this.manager = manager;
        localUsername = username;
        this.onAddContact = onAddContact;
        activePane = this;
        manager.setPresenceListener(this::contactCameOnline);
        manager.setDriveShareListener(link -> Platform.runLater(() -> {
            if (refreshSharedDrive != null) refreshSharedDrive.run();
        }));

        contactList.setPlaceholder(new Label("No saved contacts yet."));
        contactList.setItems(filteredContacts);
        contactSearch.setPromptText("Search contacts");
        contactSearch.getStyleClass().add("contact-search-field");
        contactSearch.textProperty().addListener((obs, oldValue, query) -> {
            String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
            filteredContacts.setPredicate(contact -> needle.isEmpty()
                    || contact.username().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || contact.onion().toLowerCase(java.util.Locale.ROOT).contains(needle));
        });
        contactList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ContactStore.Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                if (empty || contact == null) { setText(null); setGraphic(null); setTooltip(null); return; }
                Presence state = presence.getOrDefault(contact.link(), Presence.checking());
                Label avatar = new Label(profileInitial(contact.username()));
                avatar.getStyleClass().add("profile-bubble");
                Label dot = new Label("●");
                dot.getStyleClass().addAll("presence-dot", state.online() ? "online" : state.checkedAt() == 0 ? "checking" : "offline");
                Label name = new Label(contact.username());
                name.getStyleClass().add("contact-name");
                name.setMinWidth(0);
                HBox heading = new HBox(6, dot, name);
                Label address = new Label(contact.onion());
                address.getStyleClass().add("contact-address");
                address.setMinWidth(0);
                address.setTooltip(new Tooltip(contact.onion()));
                VBox details = new VBox(2, heading, address);
                details.setMinWidth(0);
                HBox.setHgrow(details, Priority.ALWAYS);
                HBox cell = new HBox(10, avatar, details);
                cell.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
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
            hideFloatingDate();
            transcript.getItems().clear();
            setTransferControls(false);
            updateConversationHeader();
            refreshMessages();
        });

        transcript.setPlaceholder(new Label("No messages yet."));
        transcript.getStyleClass().add("chat-transcript");
        transcript.setCellFactory(list -> new MessageCell());
        conversationAvatar.getStyleClass().add("profile-bubble");
        conversationAvatar.setVisible(false);
        conversationAvatar.managedProperty().bind(conversationAvatar.visibleProperty());
        conversationTitle.getStyleClass().add("conversation-title");
        conversationTitle.setMinWidth(0);
        conversationPresence.getStyleClass().add("conversation-presence");
        conversationPresence.setMinWidth(0);
        conversationPresence.managedProperty().bind(conversationAvatar.visibleProperty());
        Button searchMessagesButton = new Button();
        searchMessagesButton.setGraphic(searchGlyph());
        searchMessagesButton.getStyleClass().add("conversation-action-button");
        searchMessagesButton.setTooltip(new Tooltip("Search messages"));
        searchMessagesButton.setOnAction(event -> searchConversation());
        Button conversationMenu = new Button("⋮");
        conversationMenu.getStyleClass().add("conversation-menu-button");
        conversationMenu.setTooltip(new Tooltip("Conversation options"));
        conversationMenu.setFocusTraversable(false);
        VBox conversationIdentity = new VBox(2, conversationTitle, conversationPresence);
        conversationIdentity.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        conversationIdentity.setMinWidth(0);
        HBox.setHgrow(conversationIdentity, Priority.ALWAYS);
        HBox conversationHeader = new HBox(8, conversationAvatar, conversationIdentity,
                searchMessagesButton, conversationMenu);
        conversationHeader.getStyleClass().add("conversation-header");
        composer.setPromptText("Write a message…");
        composer.getStyleClass().add("chat-composer");
        composer.setSkin(new EmojiTextFieldSkin(composer));
        var hasMessage = Bindings.createBooleanBinding(() -> !composer.getText().isBlank(), composer.textProperty());
        sendButton.visibleProperty().bind(hasMessage);
        sendButton.managedProperty().bind(sendButton.visibleProperty());
        sendButton.disableProperty().bind(composer.disabledProperty().or(hasMessage.not())
                .or(messageBytes.greaterThan(MAX_BODY_BYTES)));
        voiceButton.visibleProperty().bind(hasMessage.not());
        voiceButton.managedProperty().bind(voiceButton.visibleProperty());
        attachButton.setTooltip(new Tooltip("Attach file"));
        attachButton.getStyleClass().add("attach-icon-button");
        Button emojiButton = new Button();
        emojiButton.setGraphic(smileyGlyph());
        emojiButton.getStyleClass().add("emoji-picker-button");
        emojiButton.setAccessibleText("Choose emoji");
        emojiButton.setTooltip(new Tooltip("Choose emoji"));
        emojiButton.disableProperty().bind(composer.disabledProperty());
        EmojiPicker emojiPicker = new EmojiPicker(emoji -> {
            if (composer.isDisabled()) return;
            composer.replaceSelection(emoji);
            composer.requestFocus();
        });
        emojiButton.setOnAction(event -> {
            if (emojiPicker.isShowing()) emojiPicker.hide();
            else emojiPicker.show(emojiButton, Side.TOP, 0, -6);
        });
        emojiButton.disabledProperty().addListener((obs, wasDisabled, disabled) -> {
            if (disabled) emojiPicker.hide();
        });
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
        attachmentSendButton.disableProperty().bind(composer.disabledProperty()
                .or(attachmentCaption.disabledProperty()).or(Bindings.isEmpty(pendingAttachments))
                .or(captionBytes.greaterThan(MAX_BODY_BYTES)));
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
        normalInputRow = new HBox(8, attachButton, emojiButton, composer,
                sizeCounter(composer.textProperty(), messageBytes), sendButton, voiceButton);
        normalInputRow.getStyleClass().add("chat-input-row");
        voiceControls = new HBox(12, discardVoiceButton, voiceDurationLabel, voiceWaveform, pauseVoiceButton, voiceSendButton);
        voiceControls.getStyleClass().add("voice-controls");
        voiceControls.setVisible(false);
        voiceControls.setManaged(false);
        HBox.setHgrow(voiceWaveform, Priority.ALWAYS);
        Region attachmentActionsSpacer = new Region();
        HBox.setHgrow(attachmentActionsSpacer, Priority.ALWAYS);
        HBox attachmentActions = new HBox(10, attachmentAddButton, attachmentActionsSpacer,
                sizeCounter(captionDraft, captionBytes), attachmentSendButton);
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

        status.getStyleClass().add("chat-status");
        VBox composerDock = new VBox(7, transferRow, composerArea);
        composerDock.setPadding(new Insets(8, 12, 12, 12));
        BorderPane chatContent = new BorderPane();
        transcript.setMinHeight(0);
        floatingDate.getStyleClass().addAll("chat-date-pill", "floating-chat-date");
        floatingDate.setVisible(false);
        floatingDate.setMouseTransparent(true);
        StackPane transcriptArea = new StackPane(transcript, floatingDate);
        transcriptArea.setMinHeight(0);
        StackPane.setAlignment(floatingDate, Pos.TOP_CENTER);
        StackPane.setMargin(floatingDate, new Insets(8, 16, 0, 0));
        transcript.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() != 0) trackTranscriptScroll();
        });
        transcript.addEventFilter(MouseEvent.MOUSE_PRESSED, this::trackTranscriptScrollbar);
        transcript.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::trackTranscriptScrollbar);
        transcript.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END -> trackTranscriptScroll();
                default -> { }
            }
        });
        chatContent.setCenter(transcriptArea);
        chatContent.setBottom(composerDock);

        Canvas chatPattern = new Canvas();
        // The wallpaper follows the conversation body without setting its minimum size.
        chatPattern.setManaged(false);
        chatPattern.setMouseTransparent(true);
        StackPane chatSurface = new StackPane(chatPattern, chatContent);
        chatSurface.getStyleClass().add("chat-surface");
        chatPattern.widthProperty().bind(chatSurface.widthProperty());
        chatPattern.heightProperty().bind(chatSurface.heightProperty());
        chatPattern.widthProperty().addListener(obs -> drawChatPattern(chatPattern));
        chatPattern.heightProperty().addListener(obs -> drawChatPattern(chatPattern));

        VBox conversation = new VBox(0, conversationHeader, chatSurface);
        conversation.getStyleClass().add("conversation-pane");
        conversation.setPadding(Insets.EMPTY);
        VBox.setVgrow(chatSurface, Priority.ALWAYS);
        conversation.setMinWidth(360);
        Label chatsTitle = new Label("Chats");
        chatsTitle.getStyleClass().add("contacts-title");
        Button addContact = new Button();
        addContact.setGraphic(plusGlyph());
        addContact.setTooltip(new Tooltip("Add contact"));
        addContact.getStyleClass().add("new-contact-button");
        addContact.setOnAction(event -> { if (this.onAddContact != null) this.onAddContact.run(); });
        Region contactHeaderSpacer = new Region();
        HBox.setHgrow(contactHeaderSpacer, Priority.ALWAYS);
        HBox contactsHeader = new HBox(8, chatsTitle, contactHeaderSpacer, addContact);
        contactsHeader.getStyleClass().add("contacts-header");
        Label searchIcon = new Label();
        searchIcon.setGraphic(searchGlyph());
        searchIcon.getStyleClass().add("contact-search-icon");
        HBox searchRow = new HBox(9, searchIcon, contactSearch);
        searchRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("contact-search");
        HBox.setHgrow(contactSearch, Priority.ALWAYS);
        VBox contactsPane = new VBox(8, contactsHeader, searchRow, contactList);
        contactsPane.getStyleClass().add("contacts-pane");
        VBox.setVgrow(contactList, Priority.ALWAYS);
        contactsPane.setPrefWidth(235);
        contactsPane.setMinWidth(185);
        contactsPane.setMaxWidth(400);

        Button chatsNavigation = new Button();
        chatsNavigation.setGraphic(chatsGlyph());
        chatsNavigation.setTooltip(new Tooltip("Chats"));
        chatsNavigation.getStyleClass().addAll("rail-button", "rail-button-selected");
        chatsNavigation.setFocusTraversable(false);

        Button driveNavigation = new Button();
        driveNavigation.setGraphic(driveGlyph());
        driveNavigation.getStyleClass().add("rail-button");
        driveNavigation.setAccessibleText("Drive");
        driveNavigation.setTooltip(new Tooltip("Drive"));
        driveNavigation.setFocusTraversable(false);

        Button myFilesNavigation = driveSectionButton("My files", folderGlyph());
        Button sharedWithMeNavigation = driveSectionButton("Shared with me", sharedPeopleGlyph());
        VBox driveNavigationPane = new VBox(8, new Label("Drive"), myFilesNavigation, sharedWithMeNavigation);
        driveNavigationPane.getStyleClass().add("drive-navigation-pane");
        driveNavigationPane.setPadding(new Insets(12, 8, 8, 8));
        driveNavigationPane.setPrefWidth(235);
        driveNavigationPane.setMinWidth(185);
        driveNavigationPane.setMaxWidth(400);

        VBox drivePageContent = new VBox(10);
        drivePageContent.setPadding(new Insets(28, 32, 28, 32));
        drivePageContent.getStyleClass().add("drive-page-content");
        BorderPane drivePage = new BorderPane(drivePageContent);
        drivePage.getStyleClass().add("drive-page");
        drivePage.setMinWidth(360);

        Runnable showMyFiles = () -> renderDrivePage(drivePageContent, true);
        Runnable showSharedWithMe = () -> renderDrivePage(drivePageContent, false);
        refreshSharedDrive = showSharedWithMe;
        myFilesNavigation.setOnAction(event -> {
            myFilesNavigation.getStyleClass().add("drive-nav-selected");
            sharedWithMeNavigation.getStyleClass().remove("drive-nav-selected");
            showMyFiles.run();
        });
        sharedWithMeNavigation.setOnAction(event -> {
            sharedWithMeNavigation.getStyleClass().add("drive-nav-selected");
            myFilesNavigation.getStyleClass().remove("drive-nav-selected");
            showSharedWithMe.run();
        });
        myFilesNavigation.getStyleClass().add("drive-nav-selected");
        showMyFiles.run();

        StackPane leftSection = new StackPane(contactsPane, driveNavigationPane);
        leftSection.getStyleClass().add("left-section-switcher");
        driveNavigationPane.setVisible(false);
        driveNavigationPane.setManaged(false);
        StackPane mainSection = new StackPane(conversation, drivePage);
        mainSection.getStyleClass().add("main-section-switcher");
        drivePage.setVisible(false);
        drivePage.setManaged(false);

        Runnable showChats = () -> {
            chatsNavigation.getStyleClass().add("rail-button-selected");
            driveNavigation.getStyleClass().remove("rail-button-selected");
            contactsPane.setVisible(true); contactsPane.setManaged(true);
            driveNavigationPane.setVisible(false); driveNavigationPane.setManaged(false);
            conversation.setVisible(true); conversation.setManaged(true);
            drivePage.setVisible(false); drivePage.setManaged(false);
        };
        showChatsAction = showChats;
        Runnable showDrive = () -> {
            driveNavigation.getStyleClass().add("rail-button-selected");
            chatsNavigation.getStyleClass().remove("rail-button-selected");
            contactsPane.setVisible(false); contactsPane.setManaged(false);
            driveNavigationPane.setVisible(true); driveNavigationPane.setManaged(true);
            conversation.setVisible(false); conversation.setManaged(false);
            drivePage.setVisible(true); drivePage.setManaged(true);
        };
        chatsNavigation.setOnAction(event -> showChats.run());
        driveNavigation.setOnAction(event -> showDrive.run());

        Region railDivider = new Region();
        railDivider.getStyleClass().add("rail-divider");
        Region railSpacer = new Region();
        VBox.setVgrow(railSpacer, Priority.ALWAYS);

        Label settingsIcon = railIcon("Settings",
                "M8.5 2 H11.5 L12 4.5 13.5 5.1 15.7 3.7 17.8 5.8 16.4 8 17 9.5 19.5 10 V13"
                + " L17 13.5 16.4 15 17.8 17.2 15.7 19.3 13.5 17.9 12 18.5 11.5 21 H8.5"
                + " L8 18.5 6.5 17.9 4.3 19.3 2.2 17.2 3.6 15 3 13.5 0.5 13 V10"
                + " L3 9.5 3.6 8 2.2 5.8 4.3 3.7 6.5 5.1 8 4.5 Z"
                + " M13 11.5 A3 3 0 1 1 7 11.5 A3 3 0 1 1 13 11.5 Z");
        Label profileBubble = new Label(profileInitial(username));
        profileBubble.getStyleClass().add("profile-bubble");
        profileBubble.setAccessibleText("Signed in as " + username);
        profileBubble.setTooltip(new Tooltip(username));
        VBox accountControls = new VBox(8, settingsIcon, profileBubble);
        accountControls.setAlignment(javafx.geometry.Pos.CENTER);

        torIndicatorDot.getStyleClass().addAll("tor-dot", "tor-dot-starting", "rail-tor-indicator");
        torIndicatorDot.setMinSize(28, 28);
        torIndicatorDot.setMaxSize(28, 28);
        torIndicatorDot.setAlignment(javafx.geometry.Pos.CENTER);
        torIndicatorDot.setTooltip(new Tooltip("Tor is starting"));
        torStartupPulse.setFromX(1.0);
        torStartupPulse.setFromY(1.0);
        torStartupPulse.setToX(1.28);
        torStartupPulse.setToY(1.28);
        torStartupPulse.setAutoReverse(true);
        torStartupPulse.setCycleCount(Timeline.INDEFINITE);
        torStartupPulse.play();
        VBox navigationRail = new VBox(14, torIndicatorDot, chatsNavigation,
                driveNavigation, railDivider, railSpacer, accountControls);
        navigationRail.getStyleClass().add("navigation-rail");
        navigationRail.setPrefWidth(58);
        navigationRail.setMinWidth(58);
        navigationRail.setMaxWidth(58);
        HBox leftColumns = new HBox(navigationRail, leftSection);
        HBox.setHgrow(leftSection, Priority.ALWAYS);
        leftColumns.setMinWidth(243);
        leftColumns.setPrefWidth(293);
        leftColumns.setMaxWidth(458);
        SplitPane mainSplit = new SplitPane(leftColumns, mainSection);
        mainSplit.getStyleClass().add("main-split-pane");
        mainSplit.setDividerPositions(0.25);
        mainSplit.widthProperty().addListener((obs, oldWidth, newWidth) -> clampContactColumn(mainSplit, leftColumns));
        mainSplit.getDividers().getFirst().positionProperty()
                .addListener((obs, oldPosition, newPosition) -> clampContactColumn(mainSplit, leftColumns));
        setCenter(mainSplit);
        getStyleClass().add("messenger-pane");
        contactList.getStyleClass().add("contact-list");
        setPadding(Insets.EMPTY);
        BorderPane.setMargin(leftColumns, Insets.EMPTY);

        poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshMessages()));
        poll.setCycleCount(Timeline.INDEFINITE);
        presencePoll = new Timeline(new KeyFrame(Duration.seconds(15), e -> checkContactsPresence()));
        presencePoll.setCycleCount(Timeline.INDEFINITE);
        seenPoll = new Timeline(new KeyFrame(Duration.millis(500), e -> markVisibleIncomingMessagesSeen()));
        seenPoll.setCycleCount(Timeline.INDEFINITE);
        voiceDurationPoll = new Timeline(new KeyFrame(Duration.millis(100), e -> updateVoiceDuration()));
        voiceDurationPoll.setCycleCount(Timeline.INDEFINITE);
        voicePlaybackPoll.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                poll.stop(); presencePoll.stop(); seenPoll.stop();
                hideFloatingDate();
                closeVoicePlayback();
            }
            else {
                if (!loaded) { loaded = true; loadContacts(); }
                poll.play();
                checkContactsPresence();
                presencePoll.play();
                seenPoll.play();
            }
        });
    }

    private void clampContactColumn(SplitPane split, HBox contactSide) {
        double width = split.getWidth();
        if (width <= 0 || split.getDividers().isEmpty()) return;
        double min = contactSide.minWidth(-1);
        double max = Math.min(contactSide.maxWidth(-1), width - 360);
        if (max < min) max = min;
        double current = split.getDividers().getFirst().getPosition() * width;
        double clamped = Math.max(min, Math.min(max, current));
        if (Math.abs(clamped - current) > 1) split.setDividerPosition(0, clamped / width);
    }

    static void updateTorIndicator(String message, String onionAddress) {
        MessengerPane pane = activePane;
        if (pane == null) return;
        Platform.runLater(() -> {
            Label dot = pane.torIndicatorDot;
            dot.getStyleClass().removeAll("tor-dot-starting", "tor-dot-ready", "tor-dot-failed");
            String state = message.equals("Tor ready") ? "ready"
                    : message.equals("Tor unavailable") ? "failed" : "starting";
            dot.getStyleClass().add("tor-dot-" + state);
            dot.setGraphic(null);
            dot.setText(state.equals("failed") ? "!" : "●");
            if (state.equals("starting")) pane.torStartupPulse.play();
            else {
                pane.torStartupPulse.stop();
                dot.setScaleX(1);
                dot.setScaleY(1);
            }
            Tooltip.install(dot, new Tooltip(onionAddress == null
                    ? message : "Onion service: http://" + onionAddress + "/"));
        });
    }

    private void loadContacts() {
        Thread.ofPlatform().daemon().name("messenger-contacts").start(() -> {
            try {
                List<ContactStore.Contact> contacts = manager.contacts();
                Platform.runLater(() -> {
                    ContactStore.Contact previous = selected;
                    allContacts.setAll(contacts);
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
            // Presence changes are also pushed over authenticated PINGs. This is the
            // fallback for missed announcements and matches the 15-second poll cadence.
            if (previous != null && now - previous.checkedAt() < TimeUnit.SECONDS.toMillis(15)) continue;
            if (!checkingPresence.add(contact.link())) continue;
            long requestStartedAt = System.currentTimeMillis();
            presence.putIfAbsent(contact.link(), Presence.checking());
            presenceWorkers.execute(() -> {
                boolean online = false;
                try { online = manager.isOnline(contact.link()); }
                catch (Exception ignored) { /* Unreachable and Tor errors both mean not reachable now. */ }
                boolean result = online;
                Presence oldState = presence.get(contact.link());
                if (oldState != null && oldState.checkedAt() > requestStartedAt) {
                    checkingPresence.remove(contact.link());
                    return;
                }
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

    private void contactCameOnline(String link) {
        long now = System.currentTimeMillis();
        presence.compute(link, (ignored, old) -> new Presence(true, now));
        Platform.runLater(() -> {
            contactList.refresh();
            if (selected != null && selected.link().equals(link)) updateConversationHeader();
        });
    }

    private record Presence(boolean online, long checkedAt) {
        static Presence checking() { return new Presence(false, 0); }
    }

    private void updateConversationHeader() {
        ContactStore.Contact contact = selected;
        conversationAvatar.setVisible(contact != null);
        if (contact == null) {
            conversationTitle.setText("Select a contact");
            conversationPresence.setText("");
            return;
        }
        Presence state = presence.getOrDefault(contact.link(), Presence.checking());
        conversationAvatar.setText(profileInitial(contact.username()));
        conversationTitle.setText(contact.username());
        conversationPresence.setText(state.checkedAt() == 0 ? "Checking via Tor…" : state.online() ? "Online" : "Offline or unreachable");
        conversationPresence.getStyleClass().removeAll("online", "offline", "checking");
        conversationPresence.getStyleClass().add(state.online() ? "online" : state.checkedAt() == 0 ? "checking" : "offline");
    }

    private static String profileInitial(String name) {
        if (name == null || name.isBlank()) return "?";
        return Character.toString(Character.toUpperCase(name.codePointAt(0)));
    }

    private void refreshMessages() {
        LocalDate today = LocalDate.now(TIME.getZone());
        if (!today.equals(transcriptToday.get())) transcriptToday.set(today);
        ContactStore.Contact contact = selected;
        if (contact == null || !loading.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("messenger-history").start(() -> {
            try {
                var messages = manager.messages(contact.link());
                Platform.runLater(() -> {
                    if (selected == null || !selected.link().equals(contact.link())) return;
                    Map<String, Map<Integer, String>> activeReactions = new HashMap<>();
                    List<MessageStore.Message> visibleMessages = new ArrayList<>();
                    for (MessageStore.Message message : messages) {
                        var reaction = parseReaction(message.body());
                        if (reaction == null) {
                            visibleMessages.add(message);
                        } else {
                            int actor = message.outgoing() ? 0 : 1;
                            Map<Integer, String> actors = activeReactions.computeIfAbsent(
                                    reaction.messageId(), ignored -> new HashMap<>());
                            if (reaction.added()) actors.put(actor, reaction.emoji());
                            else actors.remove(actor, reaction.emoji());
                        }
                    }
                    Map<String, Map<String, ReactionCounts>> reactions = new HashMap<>();
                    activeReactions.forEach((messageId, actors) -> {
                        Map<String, int[]> emojiCounts = new HashMap<>();
                        actors.forEach((actor, emoji) -> emojiCounts.computeIfAbsent(emoji, ignored -> new int[2])[actor]++);
                        Map<String, ReactionCounts> positiveCounts = new HashMap<>();
                        emojiCounts.forEach((emoji, counts) -> positiveCounts.put(emoji,
                                new ReactionCounts(counts[0], counts[1])));
                        if (!positiveCounts.isEmpty()) reactions.put(messageId, positiveCounts);
                    });
                    boolean reactionsChanged = !messageReactions.equals(reactions);
                    messageReactions = reactions;
                    activeTransfers.values().stream()
                            .filter(transfer -> transfer.message.contactLink().equals(contact.link()))
                            .map(transfer -> transfer.message)
                            .forEach(visibleMessages::add);
                    activeOutgoingMessages.values().stream()
                            .filter(outgoing -> outgoing.message.contactLink().equals(contact.link()))
                            .map(outgoing -> outgoing.message)
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
                        if (!visibleMessages.isEmpty() && wasAtBottom) scrollTranscriptToEnd();
                    } else if (reactionsChanged) transcript.refresh();
                    if (!sending) status.setText("");
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load conversation: " + safe(e)));
            } finally { loading.set(false); }
        });
    }

    private record MessageReaction(String messageId, String emoji, boolean added) {}
    private record ReactionCounts(int mine, int theirs) {}
    private record ReactionUpdate(String emoji, boolean added) {}

    private static String reactionBody(String messageId, String emoji, boolean added) {
        String encodedEmoji = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(emoji.getBytes(StandardCharsets.UTF_8));
        return REACTION_PREFIX + (added ? "+" : "-") + messageId + ":" + encodedEmoji;
    }

    private static MessageReaction parseReaction(String body) {
        if (body == null || !body.startsWith(REACTION_PREFIX)) return null;
        String[] fields = body.substring(REACTION_PREFIX.length()).split(":", 2);
        if (fields.length != 2) return null;
        try {
            boolean added = !fields[0].startsWith("-");
            String encodedId = fields[0].startsWith("+") || fields[0].startsWith("-")
                    ? fields[0].substring(1) : fields[0];
            String messageId = java.util.UUID.fromString(encodedId).toString();
            String emoji = new String(java.util.Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            var graphemes = EmojiArtwork.GRAPHEME.matcher(emoji);
            if (!graphemes.matches() || EmojiArtwork.imageFor(emoji) == null) return null;
            return new MessageReaction(messageId, emoji, added);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void sendReaction(MessageStore.Message target, String emoji) {
        Map<String, Integer> current = ownReactions(target.id());
        List<ReactionUpdate> changes = new ArrayList<>();
        if (current.containsKey(emoji)) {
            current.keySet().forEach(active -> changes.add(new ReactionUpdate(active, false)));
        } else {
            current.keySet().forEach(active -> changes.add(new ReactionUpdate(active, false)));
            changes.add(new ReactionUpdate(emoji, true));
        }
        sendReactionChanges(target, changes);
    }

    private Map<String, Integer> ownReactions(String messageId) {
        Map<String, Integer> current = new HashMap<>();
        messageReactions.getOrDefault(messageId, Map.of()).forEach((emoji, counts) -> {
            if (counts.mine() > 0) current.put(emoji, counts.mine());
        });
        pendingMessageReactions.getOrDefault(messageId, Map.of()).forEach((emoji, delta) -> {
            int count = Math.max(0, current.getOrDefault(emoji, 0) + delta);
            if (count == 0) current.remove(emoji);
            else current.put(emoji, count);
        });
        return current;
    }

    private void sendReactionChange(MessageStore.Message target, String emoji, boolean added) {
        sendReactionChanges(target, List.of(new ReactionUpdate(emoji, added)));
    }

    private void sendReactionChanges(MessageStore.Message target, List<ReactionUpdate> changes) {
        ContactStore.Contact contact = selected;
        if (contact == null || !contact.link().equals(target.contactLink()) || target.id().startsWith("pending-") || changes.isEmpty()) return;
        changes.forEach(change -> adjustPendingReaction(target.id(), change.emoji(), change.added() ? 1 : -1));
        transcript.refresh();
        Thread.ofPlatform().daemon().name("messenger-reaction").start(() -> {
            try {
                for (ReactionUpdate change : changes)
                    manager.send(contact.link(), reactionBody(target.id(), change.emoji(), change.added()));
                Platform.runLater(() -> {
                    changes.forEach(change -> adjustPendingReaction(target.id(), change.emoji(), change.added() ? -1 : 1));
                    refreshMessages();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    changes.forEach(change -> adjustPendingReaction(target.id(), change.emoji(), change.added() ? -1 : 1));
                    transcript.refresh();
                    status.setText("Reaction was not delivered: " + safe(e));
                });
            }
        });
    }

    private void adjustPendingReaction(String messageId, String emoji, int amount) {
        Map<String, Integer> pending = pendingMessageReactions.get(messageId);
        if (pending == null && amount < 0) return;
        if (pending == null) pending = pendingMessageReactions.computeIfAbsent(messageId, ignored -> new HashMap<>());
        int count = pending.getOrDefault(emoji, 0) + amount;
        if (count == 0) pending.remove(emoji);
        else pending.put(emoji, count);
        if (pending.isEmpty()) pendingMessageReactions.remove(messageId);
    }

    private boolean isTranscriptAtBottom() {
        if (transcript.getItems().isEmpty()) return true;
        for (Node node : transcript.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL)
                return bar.getMax() <= bar.getMin() || bar.getValue() >= bar.getMax() - 0.02;
        }
        return true;
    }

    private void scrollTranscriptToEnd() {
        // Measure the rows first, then use the index JavaFX treats as the end of the viewport.
        transcript.layout();
        transcript.scrollTo(transcript.getItems().size());
    }

    private static LocalDate messageDate(MessageStore.Message message) {
        return Instant.ofEpochMilli(message.timestamp()).atZone(TIME.getZone()).toLocalDate();
    }

    private String formatChatDate(LocalDate date) {
        LocalDate today = transcriptToday.get();
        if (date.equals(today)) return "Today";
        if (date.equals(today.minusDays(1))) return "Yesterday";
        if (date.isBefore(today) && date.isAfter(today.minusDays(7))) {
            return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        }
        return CHAT_DATE.format(date);
    }

    private void trackTranscriptScrollbar(MouseEvent event) {
        for (Node node = event.getTarget() instanceof Node target ? target : null;
             node != null && node != transcript; node = node.getParent()) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                trackTranscriptScroll();
                return;
            }
        }
    }

    private void trackTranscriptScroll() {
        lastScrollInput = System.nanoTime();
        floatingDateTimer.start();
    }

    private void hideFloatingDate() {
        floatingDateTimer.stop();
        floatingDate.setVisible(false);
    }

    private void updateFloatingDate() {
        if (transcript.lookup(".virtual-flow") instanceof VirtualFlow<?> flow
                && flow.getFirstVisibleCell() instanceof MessageCell cell && cell.getItem() != null) {
            double viewportTop = flow.localToScene(flow.getLayoutBounds()).getMinY();
            boolean inlineDateInView = cell.dateRow.isVisible()
                    && cell.dateRow.localToScene(cell.dateRow.getLayoutBounds()).getMinY() >= viewportTop;
            floatingDate.setText(formatChatDate(messageDate(cell.getItem())));
            floatingDate.setVisible(!inlineDateInView);
        } else {
            floatingDate.setVisible(false);
        }
    }

    private void markVisibleIncomingMessagesSeen() {
        if (selected == null || getScene() == null || getScene().getWindow() == null
                || !getScene().getWindow().isShowing() || !getScene().getWindow().isFocused()) return;
        javafx.geometry.Bounds viewport;
        try { viewport = transcript.localToScene(transcript.getBoundsInLocal()); }
        catch (RuntimeException e) { return; }
        for (Node node : transcript.lookupAll(".list-cell")) {
            if (!(node instanceof MessageCell cell)) continue;
            MessageStore.Message message = cell.getItem();
            if (message == null || message.outgoing() || selected == null
                    || !message.contactLink().equals(selected.link())) continue;
            Node messageRow = cell.messageLayout.getChildren().getLast();
            javafx.geometry.Bounds messageBounds = messageRow.localToScene(messageRow.getBoundsInLocal());
            if (messageBounds.getMinY() < viewport.getMinY() || messageBounds.getMaxY() > viewport.getMaxY()
                    || messageBounds.getMaxX() <= viewport.getMinX() || messageBounds.getMinX() >= viewport.getMaxX()) continue;
            String receiptKey = message.contactLink() + "\n" + message.id();
            if (readReceiptsSent.contains(receiptKey)) continue;
            long now = System.currentTimeMillis();
            Long lastAttempt = readReceiptAttempts.putIfAbsent(receiptKey, now);
            if (lastAttempt != null) {
                if (now - lastAttempt < 10_000 || !readReceiptAttempts.replace(receiptKey, lastAttempt, now)) continue;
            }
            Thread.ofPlatform().daemon().name("read-receipt").start(() -> {
                try {
                    manager.sendReadReceipt(message.contactLink(), message.id());
                    readReceiptsSent.add(receiptKey);
                } catch (Exception e) {
                    System.err.println("Could not send read receipt: " + safe(e));
                }
            });
        }
    }

    private boolean isLatestOutgoing(String id) {
        List<MessageStore.Message> messages = transcript.getItems();
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageStore.Message message = messages.get(i);
            if (message.outgoing()) return message.id().equals(id);
        }
        return false;
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
                scrollTranscriptToEnd();
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
        final javafx.beans.property.BooleanProperty sent = new javafx.beans.property.SimpleBooleanProperty(false);
        final StringProperty speed = new SimpleStringProperty("0 B/s");

        ActiveTransfer(MessageStore.Message message, AtomicBoolean cancellation) {
            this.message = message;
            this.cancellation = cancellation;
        }
    }

    private static final class ActiveOutgoingMessage {
        final MessageStore.Message message;
        final StringProperty status = new SimpleStringProperty("Sending…");
        ActiveOutgoingMessage(MessageStore.Message message) { this.message = message; }
    }

    private void send() {
        ContactStore.Contact contact = selected;
        String body = composer.getText();
        if (contact == null || body == null || body.isBlank() || sending || messageBytes.get() > MAX_BODY_BYTES) return;
        String pendingId = "pending-" + java.util.UUID.randomUUID();
        MessageStore.Message pendingMessage = new MessageStore.Message(pendingId, contact.link(), true,
                body, null, null, 0, System.currentTimeMillis());
        ActiveOutgoingMessage activeMessage = new ActiveOutgoingMessage(pendingMessage);
        activeOutgoingMessages.put(pendingId, activeMessage);
        transcript.getItems().add(pendingMessage);
        scrollTranscriptToEnd();
        sending = true;
        setTransferControls(true);
        status.setText("Sending message…");
        Thread.ofPlatform().daemon().name("messenger-send").start(() -> {
            try {
                manager.send(contact.link(), body, message -> Platform.runLater(() -> {
                    if ("MESSAGE_SENT".equals(message)) activeMessage.status.set("✓ Sent");
                    else if (sending && selected != null && selected.link().equals(contact.link())) status.setText(message);
                }));
                activeOutgoingMessages.remove(pendingId);
                Platform.runLater(() -> {
                    if (selected != null && selected.link().equals(contact.link()) && composer.getText().equals(body))
                        composer.clear();
                    sending = false;
                    setTransferControls(false);
                    status.setText("Message delivered and saved.");
                    refreshMessages();
                });
            } catch (Exception e) {
                activeOutgoingMessages.remove(pendingId);
                Platform.runLater(() -> {
                    transcript.getItems().removeIf(message -> message.id().equals(pendingId));
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
                                double fileFraction = fileSize == 0 ? 1 : Math.min(1, Math.max(0, (double) transferred / fileSize));
                                if (transferred > 0) bubble.sent.set(true);
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
                            if (transferred > 0) activeBubble.sent.set(true);
                            activeBubble.progress.set(size == 0 ? 1.0 : Math.min(1, Math.max(0, (double) transferred / size)));
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
        if (sending || pendingAttachments.isEmpty() || selected == null || captionBytes.get() > MAX_BODY_BYTES) return;
        String caption = captionDraft.get();
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
        attachButton.setDisable(busy || selected == null);
        voiceButton.setDisable(busy || selected == null);
        composer.setDisable(busy || selected == null);
        attachmentCaption.setDisable(busy);
        attachmentAddButton.setDisable(busy || pendingAttachments.size() >= 5 || selected == null);
    }

    private static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Label sizeCounter(ObservableStringValue draft, IntegerBinding bytes) {
        Label counter = new Label();
        counter.getStyleClass().add("composer-size-counter");
        counter.setMinWidth(Region.USE_PREF_SIZE);
        counter.setTextAlignment(TextAlignment.RIGHT);
        counter.textProperty().bind(Bindings.createStringBinding(() -> {
            int used = bytes.get();
            if (used < COUNTER_THRESHOLD) return "";
            var characters = EmojiArtwork.GRAPHEME.matcher(draft.get());
            int count = 0;
            while (characters.find()) count++;
            String unit = count == 1 ? "character" : "characters";
            int remaining = MAX_BODY_BYTES - used;
            String allowance = switch (Integer.signum(remaining)) {
                case -1 -> "Too long";
                case 0 -> "0 left";
                default -> String.format(Locale.ROOT, "Up to %,d left", remaining);
            };
            return String.format(Locale.ROOT, "%,d %s\n%s", count, unit, allowance);
        }, draft, bytes));
        counter.visibleProperty().bind(bytes.greaterThanOrEqualTo(COUNTER_THRESHOLD));
        counter.managedProperty().bind(counter.visibleProperty());
        PseudoClass overLimit = PseudoClass.getPseudoClass("over-limit");
        bytes.addListener((obs, oldCount, newCount) ->
                counter.pseudoClassStateChanged(overLimit, newCount.intValue() > MAX_BODY_BYTES));
        return counter;
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

    private static SVGPath smileyGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10 Z M6.8 8 H7 M13 8 H13.2 M6.5 12 Q10 16 13.5 12");
        icon.getStyleClass().add("emoji-picker-glyph");
        return icon;
    }

    private static SVGPath searchGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M10.8 3a7.8 7.8 0 1 0 4.8 13.95L21 22l1-1-5.4-5.05A7.8 7.8 0 0 0 10.8 3zm0 1.6a6.2 6.2 0 1 1 0 12.4 6.2 6.2 0 0 1 0-12.4z");
        icon.getStyleClass().add("contact-search-glyph");
        return icon;
    }

    private static void drawChatPattern(Canvas canvas) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, width, height);
        graphics.setStroke(javafx.scene.paint.Color.rgb(105, 125, 147, 0.12));
        graphics.setLineWidth(1.15);
        graphics.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        double stepX = 68;
        double stepY = 68;
        for (int row = -1; row * stepY < height + stepY; row++) {
            double cy = row * stepY + 34;
            double offset = (row & 1) == 0 ? 0 : stepX / 2;
            for (double cx = -stepX + offset; cx < width + stepX; cx += stepX) {
                // Repeating nested angular arches, offset by half a tile per row
                // to create the interlocking textile-like pattern from the reference.
                for (int layer = 0; layer < 3; layer++) {
                    double halfWidth = 31 - layer * 8;
                    double top = cy - 24 + layer * 7;
                    double bottom = cy + 24 - layer * 7;
                    graphics.beginPath();
                    graphics.moveTo(cx - halfWidth, bottom);
                    graphics.lineTo(cx - halfWidth, top);
                    graphics.lineTo(cx, top - 13 + layer * 4);
                    graphics.lineTo(cx + halfWidth, top);
                    graphics.lineTo(cx + halfWidth, bottom);
                    graphics.stroke();
                }
            }
        }
    }

    private static Label railIcon(String name, String path) {
        SVGPath glyph = new SVGPath();
        glyph.setContent(path);
        glyph.getStyleClass().add("rail-icon-glyph");
        Label icon = new Label(null, glyph);
        icon.getStyleClass().add("rail-icon");
        icon.setAccessibleText(name);
        icon.setTooltip(new Tooltip(name));
        return icon;
    }

    private static Button driveSectionButton(String title, SVGPath glyph) {
        Button button = new Button(title, glyph);
        button.getStyleClass().add("drive-section-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setFocusTraversable(false);
        return button;
    }

    private static SVGPath driveGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M3 13 L6 6 H18 L21 13 M3 13 H21 V19 H3 Z M17 16 H18");
        icon.getStyleClass().add("rail-icon-glyph");
        return icon;
    }

    private static SVGPath folderGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M3 6 H9 L11 8 H21 V19 H3 Z");
        icon.getStyleClass().add("drive-section-glyph");
        return icon;
    }

    private static SVGPath filePlusGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M12.5 12 V18 M9.5 15 H15.5");
        icon.getStyleClass().add("drive-action-glyph");
        return icon;
    }

    private static SVGPath folderPlusGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M2.5 6 H9 L11 8 H21.5 V19 H2.5 Z M12 10.5 V16.5 M9 13.5 H15");
        icon.getStyleClass().add("drive-action-glyph");
        return icon;
    }

    private static SVGPath fileGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19");
        icon.getStyleClass().add("drive-entry-glyph");
        return icon;
    }

    private static SVGPath driveMoreGlyph() {
        return driveMenuGlyph("M12 5 L12 5 M12 12 L12 12 M12 19 L12 19");
    }

    private static SVGPath driveMenuGlyph(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("drive-menu-glyph");
        return icon;
    }

    private static SVGPath sharedPeopleGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M9 11 A3.5 3.5 0 1 0 9 4 A3.5 3.5 0 1 0 9 11 Z M2 20 V18 A4 4 0 0 1 6 14 H12 A4 4 0 0 1 16 18 V20 M16 5 A3 3 0 0 1 16 11 M18 14 A4 4 0 0 1 22 18 V20");
        icon.getStyleClass().add("drive-section-glyph");
        return icon;
    }

    private void renderDrivePage(VBox content, boolean myFiles) {
        renderDrivePage(content, myFiles, AppData.directory().resolve("drive"));
    }

    private void renderDrivePage(VBox content, boolean myFiles, Path currentDirectory) {
        content.getChildren().clear();
        SVGPath headingGlyph = myFiles ? folderGlyph() : sharedPeopleGlyph();
        Label title = new Label(myFiles ? "My files" : "Shared with me", headingGlyph);
        title.getStyleClass().add("drive-page-title");
        Label subtitle = new Label(myFiles
                ? "Files and folders added from this device are managed here."
                : "People who share files with you and their available files will appear here.");
        subtitle.getStyleClass().add("drive-page-subtitle");
        VBox heading = new VBox(5, title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button addFiles = new Button();
        addFiles.setGraphic(filePlusGlyph());
        addFiles.getStyleClass().addAll("drive-add-files-button", "drive-icon-action-button");
        addFiles.setAccessibleText("Add files");
        addFiles.setTooltip(new Tooltip("Add files"));
        addFiles.setDisable(!myFiles);
        Button addFolder = new Button();
        addFolder.setGraphic(folderPlusGlyph());
        addFolder.getStyleClass().addAll("drive-add-folder-button", "drive-icon-action-button");
        addFolder.setAccessibleText("Add folder");
        addFolder.setTooltip(new Tooltip("Add folder"));
        addFolder.setDisable(!myFiles);
        Button addButton = new Button("New");
        addButton.setGraphic(plusGlyph());
        addButton.getStyleClass().add("drive-new-button");
        addButton.setAccessibleText("Create a folder or text file");
        addButton.setTooltip(new Tooltip("Create a folder or text file"));
        addButton.setDisable(!myFiles);
        ProgressIndicator importing = new ProgressIndicator();
        importing.setMaxSize(18, 18);
        importing.setVisible(false);
        importing.setManaged(false);
        Label feedback = new Label();
        feedback.setVisible(false);
        feedback.setManaged(false);
        feedback.getStyleClass().add("drive-page-subtitle");
        addFiles.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Add files to My files");
            List<File> selectedFiles = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
            if (selectedFiles != null && !selectedFiles.isEmpty()) {
                importIntoDrive(selectedFiles.stream().map(File::toPath).toList(), content, currentDirectory, addFiles, addFolder, importing, feedback);
            }
        });
        addFolder.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Add folder to My files");
            File selected = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (selected != null) {
                importIntoDrive(List.of(selected.toPath()), content, currentDirectory, addFiles, addFolder, importing, feedback);
            }
        });
        addButton.setOnAction(event -> {
            ContextMenu addMenu = new ContextMenu();
            addMenu.getStyleClass().add("drive-entry-menu");
            MenuItem createFolder = driveMenuItem("Folder", "M3 7h7l2 2h9v11H3z M3 9h18", () -> createDriveEntry(currentDirectory, content, true));
            MenuItem createTextFile = driveMenuItem("Text file", "M6 3h8l4 4v14H6z M14 3v5h5", () -> createDriveEntry(currentDirectory, content, false));
            addMenu.getItems().setAll(createFolder, createTextFile);
            addMenu.show(addButton, Side.BOTTOM, 0, 2);
        });
        HBox actionGroup = new HBox(8, importing, addFiles, addFolder);
        actionGroup.setAlignment(Pos.CENTER_RIGHT);
        actionGroup.getStyleClass().add("drive-header-actions");
        HBox header = new HBox(16, heading, spacer, actionGroup);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("drive-page-header");

        VBox pageBody = new VBox(0);
        pageBody.setAlignment(Pos.TOP_LEFT);
        if (myFiles) {
            Path driveDirectory = AppData.directory().resolve("drive");
            try {
                Files.createDirectories(driveDirectory);
                Path driveRoot = driveDirectory.toAbsolutePath().normalize();
                Path visibleDirectory = currentDirectory.toAbsolutePath().normalize();
                if (!visibleDirectory.startsWith(driveRoot)) visibleDirectory = driveRoot;
                pageBody.getChildren().add(driveBreadcrumb(driveRoot, visibleDirectory, content, addButton));
                List<Path> entries;
                try (var children = Files.list(visibleDirectory)) {
                    Comparator<Path> comparator = switch (driveSortColumn) {
                        case NAME -> Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
                        case TYPE -> Comparator.comparing(path -> Files.isDirectory(path) ? "Folder" : fileTypeLabel(path), String.CASE_INSENSITIVE_ORDER);
                        case SIZE -> Comparator.comparingLong(path -> Files.isDirectory(path) ? 0 : fileSize(path));
                    };
                    comparator = comparator.thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
                    if (!driveSortAscending) comparator = comparator.reversed();
                    entries = children.sorted(comparator).toList();
                }
                if (entries.isEmpty()) {
                    VBox empty = emptyDriveState(true);
                    if (!visibleDirectory.equals(driveRoot)) {
                        ((Label) empty.getChildren().get(1)).setText("This folder is empty");
                        ((Label) empty.getChildren().get(2)).setText("Add files here or go back to My files.");
                    }
                    pageBody.getChildren().add(empty);
                } else {
                    int pageCount = (entries.size() + drivePageSize - 1) / drivePageSize;
                    drivePageIndex = Math.max(0, Math.min(drivePageIndex, pageCount - 1));
                    int firstEntry = drivePageIndex * drivePageSize;
                    int endEntry = Math.min(entries.size(), firstEntry + drivePageSize);
                    pageBody.getChildren().add(driveTableHeader(content, visibleDirectory));
                    for (Path entry : entries.subList(firstEntry, endEntry)) {
                        HBox row = driveEntryRow(entry, content);
                        row.setOnMouseClicked(event -> {
                            if (event.getClickCount() == 2 && Files.isDirectory(entry)) {
                                renderDrivePage(content, true, entry);
                            }
                        });
                        pageBody.getChildren().add(row);
                    }
                    content.getProperties().put("drivePagination", drivePagination(content, visibleDirectory,
                            entries.size(), firstEntry, endEntry, pageCount));
                }
            } catch (IOException error) {
                feedback.setText("Could not read your Drive folder: " + error.getMessage());
                feedback.setManaged(true);
                feedback.setVisible(true);
                pageBody.getChildren().add(emptyDriveState(true));
            }
        } else {
            content.getProperties().remove("drivePagination");
            try {
                List<SharedDriveStore.SharedFile> shared = manager.sharedDriveFiles().stream()
                        .filter(file -> Files.isRegularFile(Path.of(file.path())))
                        .sorted(switch (driveSortColumn) {
                            case NAME -> Comparator.comparing(SharedDriveStore.SharedFile::name, String.CASE_INSENSITIVE_ORDER);
                            case TYPE -> Comparator.comparing(file -> fileTypeLabel(Path.of(file.name())), String.CASE_INSENSITIVE_ORDER);
                            case SIZE -> Comparator.comparingLong(SharedDriveStore.SharedFile::size);
                        }).toList();
                if (!driveSortAscending) {
                    var reversed = new ArrayList<>(shared);
                    java.util.Collections.reverse(reversed);
                    shared = List.copyOf(reversed);
                }
                if (shared.isEmpty()) pageBody.getChildren().add(emptyDriveState(false));
                else {
                    int pageCount = (shared.size() + drivePageSize - 1) / drivePageSize;
                    drivePageIndex = Math.max(0, Math.min(drivePageIndex, pageCount - 1));
                    int firstEntry = drivePageIndex * drivePageSize;
                    int endEntry = Math.min(shared.size(), firstEntry + drivePageSize);
                    pageBody.getChildren().add(sharedDriveTableHeader());
                    Map<String, String> names = new HashMap<>();
                    for (ContactStore.Contact contact : allContacts) names.put(contact.link(), contact.username());
                    for (SharedDriveStore.SharedFile file : shared.subList(firstEntry, endEntry))
                        pageBody.getChildren().add(sharedDriveEntryRow(file, names.getOrDefault(file.contactLink(), "Contact")));
                    content.getProperties().put("drivePagination", drivePagination(content, AppData.directory().resolve("drive"),
                            shared.size(), firstEntry, endEntry, pageCount));
                }
            } catch (Exception error) {
                feedback.setText("Could not read Shared with me: " + error.getMessage());
                feedback.setManaged(true);
                feedback.setVisible(true);
                pageBody.getChildren().add(emptyDriveState(false));
            }
        }
        ScrollPane bodyViewport = new ScrollPane(pageBody);
        bodyViewport.setFitToWidth(true);
        bodyViewport.setFitToHeight(true);
        bodyViewport.getStyleClass().add("drive-items-viewport");
        VBox.setVgrow(bodyViewport, Priority.ALWAYS);
        content.getChildren().addAll(header, feedback, bodyViewport);
        Object pagination = content.getProperties().remove("drivePagination");
        if (pagination instanceof Node node) content.getChildren().add(node);
    }

    private HBox driveBreadcrumb(Path root, Path current, VBox content, Button addButton) {
        Button parentButton = new Button("‹");
        parentButton.getStyleClass().add("drive-history-button");
        parentButton.setAlignment(Pos.CENTER);
        parentButton.setMinHeight(30);
        parentButton.setPrefHeight(30);
        parentButton.setMaxHeight(30);
        parentButton.setAccessibleText("Back to parent folder");
        parentButton.setDisable(current.equals(root));
        parentButton.setOnAction(event -> {
            Path parent = current.getParent();
            if (parent != null && parent.startsWith(root)) renderDrivePage(content, true, parent);
        });

        HBox crumbs = new HBox(7);
        crumbs.setMinWidth(0);
        crumbs.setMaxWidth(Double.MAX_VALUE);
        Button rootButton = new Button("My files");
        rootButton.getStyleClass().add(current.equals(root) ? "drive-crumb-current" : "drive-crumb");
        rootButton.setMinWidth(0);
        rootButton.setMaxWidth(120);
        rootButton.setAlignment(Pos.CENTER);
        rootButton.setMinHeight(30);
        rootButton.setPrefHeight(30);
        rootButton.setMaxHeight(30);
        rootButton.setTextOverrun(OverrunStyle.ELLIPSIS);
        rootButton.setOnAction(event -> renderDrivePage(content, true, root));
        crumbs.getChildren().add(rootButton);

        List<Path> parts = new ArrayList<>();
        for (Path part : root.relativize(current)) parts.add(part);
        int visibleStart = Math.max(0, parts.size() - 2);
        if (visibleStart > 0) {
            Label slash = new Label("/");
            slash.getStyleClass().add("drive-breadcrumb-slash");
            slash.setAlignment(Pos.CENTER);
            Path collapsedTarget = root;
            for (int i = 0; i < visibleStart; i++) collapsedTarget = collapsedTarget.resolve(parts.get(i));
            Path collapsedDestination = collapsedTarget;
            Button collapsed = new Button("…");
            collapsed.getStyleClass().add("drive-crumb");
            collapsed.setAccessibleText("Open parent folders");
            collapsed.setMinWidth(0);
            collapsed.setMaxWidth(42);
            collapsed.setAlignment(Pos.CENTER);
            collapsed.setMinHeight(30);
            collapsed.setPrefHeight(30);
            collapsed.setMaxHeight(30);
            collapsed.setOnAction(event -> renderDrivePage(content, true, collapsedDestination));
            crumbs.getChildren().addAll(slash, collapsed);
        }

        Path cursor = root;
        for (int i = 0; i < parts.size(); i++) {
            Path part = parts.get(i);
            cursor = cursor.resolve(part);
            if (i < visibleStart) continue;
            Label slash = new Label("/");
            slash.getStyleClass().add("drive-breadcrumb-slash");
            slash.setAlignment(Pos.CENTER);
            Path target = cursor;
            Button crumb = new Button(part.toString());
            crumb.getStyleClass().add(cursor.equals(current) ? "drive-crumb-current" : "drive-crumb");
            crumb.setMinWidth(0);
            crumb.setMaxWidth(170);
            crumb.setAlignment(Pos.CENTER);
            crumb.setMinHeight(30);
            crumb.setPrefHeight(30);
            crumb.setMaxHeight(30);
            crumb.setTextOverrun(OverrunStyle.ELLIPSIS);
            crumb.setOnAction(event -> renderDrivePage(content, true, target));
            crumbs.getChildren().addAll(slash, crumb);
        }
        HBox bar = new HBox(10, parentButton, crumbs, addButton);
        HBox.setHgrow(crumbs, Priority.ALWAYS);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("drive-breadcrumb-bar");
        return bar;
    }

    private GridPane driveTableHeader(VBox content, Path directory) {
        GridPane header = new GridPane();
        header.setAlignment(Pos.CENTER);
        header.getColumnConstraints().setAll(driveColumns());
        Label name = driveColumnLabel("Name", DriveSortColumn.NAME, content, directory);
        Label type = driveColumnLabel("Type", DriveSortColumn.TYPE, content, directory);
        Label size = driveColumnLabel("Size", DriveSortColumn.SIZE, content, directory);
        name.setAlignment(Pos.CENTER_LEFT);
        header.add(name, 0, 0);
        header.add(type, 1, 0);
        header.add(size, 2, 0);
        javafx.scene.layout.GridPane.setValignment(name, javafx.geometry.VPos.CENTER);
        javafx.scene.layout.GridPane.setValignment(type, javafx.geometry.VPos.CENTER);
        javafx.scene.layout.GridPane.setValignment(size, javafx.geometry.VPos.CENTER);
        header.getStyleClass().add("drive-table-header");
        return header;
    }

    private GridPane sharedDriveTableHeader() {
        GridPane header = new GridPane();
        header.getColumnConstraints().setAll(sharedDriveColumns());
        for (int i = 0; i < 4; i++) {
            Label label = new Label(List.of("Name", "Type", "Size", "Shared by").get(i));
            label.getStyleClass().add("drive-table-heading");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setAlignment(i == 0 ? Pos.CENTER_LEFT : Pos.CENTER);
            header.add(label, i, 0);
        }
        header.getStyleClass().add("drive-table-header");
        return header;
    }

    private List<javafx.scene.layout.ColumnConstraints> sharedDriveColumns() {
        List<javafx.scene.layout.ColumnConstraints> columns = new ArrayList<>();
        for (double width : new double[]{48, 17, 17, 18}) {
            var column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(width);
            column.setHgrow(Priority.SOMETIMES);
            columns.add(column);
        }
        return columns;
    }

    private HBox sharedDriveEntryRow(SharedDriveStore.SharedFile file, String sender) {
        Path path = Path.of(file.path());
        SVGPath glyph = fileGlyph();
        StackPane icon = new StackPane(glyph);
        icon.getStyleClass().add("drive-entry-icon");
        Label name = new Label(file.name());
        name.getStyleClass().add("drive-entry-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox nameCell = new HBox(12, icon, name);
        nameCell.setAlignment(Pos.CENTER_LEFT);
        Label type = new Label(fileTypeLabel(path));
        Label size = new Label(formatBytes(file.size()));
        Label sharedBy = new Label(sender);
        for (Label label : List.of(type, size, sharedBy)) {
            label.getStyleClass().add("drive-table-cell");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setAlignment(Pos.CENTER);
        }
        GridPane cells = new GridPane();
        cells.getColumnConstraints().setAll(sharedDriveColumns());
        cells.add(nameCell, 0, 0); cells.add(type, 1, 0); cells.add(size, 2, 0); cells.add(sharedBy, 3, 0);
        HBox row = new HBox(cells);
        HBox.setHgrow(cells, Priority.ALWAYS);
        row.getStyleClass().add("drive-entry-row");
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && Files.isRegularFile(path)) {
                try { java.awt.Desktop.getDesktop().open(path.toFile()); }
                catch (Exception error) { showDriveAlert(Alert.AlertType.ERROR, "Open file", "Could not open this file: " + error.getMessage()); }
            }
        });
        Tooltip.install(row, new Tooltip("Received from " + sender + " · double-click to open"));
        return row;
    }

    private List<javafx.scene.layout.ColumnConstraints> driveColumns() {
        List<javafx.scene.layout.ColumnConstraints> columns = new ArrayList<>();
        for (double width : new double[]{54, 19, 19, 8}) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(width);
            column.setHgrow(Priority.SOMETIMES);
            columns.add(column);
        }
        return columns;
    }

    private Label driveColumnLabel(String text, DriveSortColumn column, VBox content, Path directory) {
        String indicator = driveSortColumn == column ? (driveSortAscending ? "  ↑" : "  ↓") : "";
        Label label = new Label(text + indicator);
        label.getStyleClass().addAll("drive-table-heading", "drive-sort-heading");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.setAccessibleText("Sort by " + text + (driveSortColumn == column
                ? (driveSortAscending ? " ascending" : " descending") : ""));
        label.setOnMouseClicked(event -> {
            if (driveSortColumn == column) driveSortAscending = !driveSortAscending;
            else {
                driveSortColumn = column;
                driveSortAscending = true;
            }
            drivePageIndex = 0;
            renderDrivePage(content, true, directory);
        });
        return label;
    }

    private HBox drivePagination(VBox content, Path directory, int totalEntries,
                                 int firstEntry, int endEntry, int pageCount) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label pageSizeLabel = new Label("Rows per page");
        pageSizeLabel.getStyleClass().add("drive-pagination-label");
        ComboBox<Integer> pageSize = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100));
        pageSize.setValue(drivePageSize);
        pageSize.getStyleClass().add("drive-page-size");
        pageSize.setAccessibleText("Rows per page");
        pageSize.setOnAction(event -> {
            Integer selectedSize = pageSize.getValue();
            if (selectedSize != null && selectedSize != drivePageSize) {
                drivePageSize = selectedSize;
                drivePageIndex = 0;
                renderDrivePage(content, true, directory);
            }
        });
        Label range = new Label((firstEntry + 1) + "–" + endEntry + " of " + totalEntries);
        range.getStyleClass().add("drive-pagination-label");
        Button previous = new Button("‹");
        previous.getStyleClass().add("drive-page-button");
        previous.setAccessibleText("Previous page");
        previous.setDisable(drivePageIndex == 0);
        previous.setOnAction(event -> {
            drivePageIndex--;
            renderDrivePage(content, true, directory);
        });
        Button next = new Button("›");
        next.getStyleClass().add("drive-page-button");
        next.setAccessibleText("Next page");
        next.setDisable(drivePageIndex >= pageCount - 1);
        next.setOnAction(event -> {
            drivePageIndex++;
            renderDrivePage(content, true, directory);
        });
        HBox controls = new HBox(8, pageSizeLabel, pageSize, range, previous, next);
        controls.setAlignment(Pos.CENTER_RIGHT);
        HBox footer = new HBox(spacer, controls);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("drive-pagination");
        return footer;
    }

    private VBox emptyDriveState(boolean myFiles) {
        SVGPath emptyGlyph = myFiles ? folderGlyph() : sharedPeopleGlyph();
        Label emptyIcon = new Label(null, emptyGlyph);
        emptyIcon.getStyleClass().add("drive-empty-icon");
        Label emptyTitle = new Label(myFiles ? "No files or folders yet" : "No one has shared files with you yet");
        emptyTitle.getStyleClass().add("drive-empty-title");
        Label emptyDescription = new Label(myFiles
                ? "Add files or a folder from this device. Copies are stored in the app's local Drive folder."
                : "People who share files with you will appear here. Select someone to browse their files.");
        emptyDescription.getStyleClass().add("drive-empty-description");
        emptyDescription.setWrapText(true);
        emptyDescription.setMaxWidth(420);
        VBox emptyState = new VBox(13, emptyIcon, emptyTitle, emptyDescription);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("drive-empty-state");
        VBox.setVgrow(emptyState, Priority.ALWAYS);
        return emptyState;
    }

    private HBox driveEntryRow(Path entry, VBox content) {
        boolean directory = Files.isDirectory(entry);
        SVGPath glyph = directory ? folderGlyph() : fileGlyph();
        glyph.getStyleClass().clear();
        glyph.getStyleClass().add("drive-entry-glyph");
        Label icon = new Label(null, glyph);
        icon.getStyleClass().add("drive-entry-icon");
        Label name = new Label(entry.getFileName().toString());
        name.getStyleClass().add("drive-entry-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        name.setMaxWidth(320);
        HBox nameCell = new HBox(12, icon, name);
        nameCell.setAlignment(Pos.CENTER_LEFT);
        Label type = new Label(directory ? "Folder" : fileTypeLabel(entry));
        Label size = new Label(directory ? "—" : formatBytes(fileSize(entry)));
        type.getStyleClass().add("drive-table-cell");
        size.getStyleClass().add("drive-table-cell");
        type.setMaxWidth(Double.MAX_VALUE);
        type.setAlignment(Pos.CENTER);
        size.setMaxWidth(Double.MAX_VALUE);
        size.setAlignment(Pos.CENTER);
        Button more = new Button();
        more.setGraphic(driveMoreGlyph());
        more.setTooltip(new Tooltip("More options"));
        more.setAccessibleText("More options for " + entry.getFileName());
        more.getStyleClass().add("drive-entry-more");
        more.setOnAction(event -> showDriveEntryMenu(more, entry, content));
        GridPane cells = new GridPane();
        cells.setAlignment(Pos.CENTER);
        cells.getColumnConstraints().setAll(driveColumns());
        cells.add(nameCell, 0, 0);
        cells.add(type, 1, 0);
        cells.add(size, 2, 0);
        cells.add(more, 3, 0);
        javafx.scene.layout.GridPane.setValignment(nameCell, javafx.geometry.VPos.CENTER);
        javafx.scene.layout.GridPane.setValignment(type, javafx.geometry.VPos.CENTER);
        javafx.scene.layout.GridPane.setValignment(size, javafx.geometry.VPos.CENTER);
        javafx.scene.layout.GridPane.setValignment(more, javafx.geometry.VPos.CENTER);
        cells.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(cells);
        HBox.setHgrow(cells, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("drive-entry-row");
        return row;
    }

    private void showDriveEntryMenu(Button anchor, Path entry, VBox content) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("drive-entry-menu");
        MenuItem rename = driveMenuItem("Rename", "M4 20h16 M14.5 4.5l5 5 M13 6 5 14l-1 5 5-1 8-8", () -> renameDriveEntry(entry, content));
        MenuItem share = driveMenuItem("Share", "M18 8a3 3 0 1 0-2.9-3.8L8.6 10.7 M8.6 13.3l6.8 4.4 M9 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M21 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M21 19a3 3 0 1 1-6 0 3 3 0 0 1 6 0", () -> shareDriveEntry(entry));
        MenuItem delete = driveMenuItem("Delete", "M3 6h18 M8 6V4h8v2 M6 6l1 15h10l1-15", () -> deleteDriveEntry(entry, content));
        MenuItem properties = driveMenuItem("Properties", "M12 8h.01 M11 12h1v4h1 M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0Z", () -> showDriveProperties(entry));
        menu.getItems().setAll(rename, share, delete, new SeparatorMenuItem(), properties);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    private MenuItem driveMenuItem(String title, String iconPath, Runnable action) {
        MenuItem item = new MenuItem(title);
        item.setGraphic(driveMenuGlyph(iconPath));
        item.setOnAction(event -> action.run());
        return item;
    }

    private void createDriveEntry(Path currentDirectory, VBox content, boolean folder) {
        Dialog<String> dialog = new Dialog<>();
        String label = folder ? "Folder" : "Text file";
        dialog.setTitle("Create " + label.toLowerCase(Locale.ROOT));
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.initStyle(StageStyle.TRANSPARENT);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("drive-rename-dialog");
        pane.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());

        String icon = folder ? "M3 7h7l2 2h9v11H3z M3 9h18" : "M6 3h8l4 4v14H6z M14 3v5h5";
        HBox titleBar = driveDialogTitleBar(dialog, "New " + label.toLowerCase(Locale.ROOT), icon, false);
        Label prompt = new Label(folder ? "Folder name" : "File name");
        prompt.getStyleClass().add("drive-rename-prompt");
        TextField nameField = new TextField(folder ? "New folder" : "New text file.txt");
        nameField.getStyleClass().add("drive-rename-field");
        nameField.setMaxWidth(Double.MAX_VALUE);
        VBox body = new VBox(10, titleBar, prompt, nameField);
        body.getStyleClass().add("drive-rename-body");
        pane.setContent(body);
        pane.getButtonTypes().setAll(
                new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE),
                new ButtonType("Create", ButtonBar.ButtonData.OK_DONE));
        ((Button) pane.lookupButton(pane.getButtonTypes().get(1))).getStyleClass().add("drive-dialog-primary");
        pane.setPrefWidth(390);
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? nameField.getText() : null);
        dialog.setOnShown(event -> {
            dialog.getDialogPane().getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
            nameField.requestFocus();
            nameField.selectAll();
        });

        dialog.showAndWait().ifPresent(rawName -> {
            String name = rawName.strip();
            if (name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")) {
                showDriveAlert(Alert.AlertType.ERROR, "Invalid name", "Enter a valid file or folder name.");
                return;
            }
            if (!folder && !name.toLowerCase(Locale.ROOT).endsWith(".txt")) name += ".txt";

            Path root = AppData.directory().resolve("drive").toAbsolutePath().normalize();
            Path directory = currentDirectory.toAbsolutePath().normalize();
            if (!directory.startsWith(root)) directory = root;
            Path destination = directory.resolve(name).normalize();
            try {
                Files.createDirectories(directory);
                if (folder) Files.createDirectory(destination);
                else Files.createFile(destination);
                renderDrivePage(content, true, directory);
            } catch (java.nio.file.FileAlreadyExistsException error) {
                showDriveAlert(Alert.AlertType.ERROR, "Name already exists", "An item with that name is already in this folder.");
            } catch (IOException error) {
                showDriveAlert(Alert.AlertType.ERROR, "Could not create item", error.getMessage());
            }
        });
    }

    private void renameDriveEntry(Path entry, VBox content) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Rename");
        dialog.initOwner(getScene().getWindow());
        dialog.initStyle(StageStyle.TRANSPARENT);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("drive-rename-dialog");
        pane.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());

        HBox titleBar = driveDialogTitleBar(dialog, "Rename", "M4 20h16 M14.5 4.5l5 5 M13 6 5 14l-1 5 5-1 8-8", false);
        Label itemName = new Label(entry.getFileName().toString());
        itemName.getStyleClass().add("drive-rename-item");
        Label prompt = new Label("New name");
        prompt.getStyleClass().add("drive-rename-prompt");
        TextField nameField = new TextField(entry.getFileName().toString());
        nameField.getStyleClass().add("drive-rename-field");
        nameField.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(7, prompt, nameField);
        VBox body = new VBox(5, titleBar, itemName, form);
        body.getStyleClass().add("drive-rename-body");
        pane.setContent(body);
        pane.getButtonTypes().setAll(
                new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE),
                new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE));
        Button renameButton = (Button) pane.lookupButton(pane.getButtonTypes().get(1));
        renameButton.getStyleClass().add("drive-dialog-primary");
        pane.setPrefWidth(390);
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? nameField.getText() : null);
        dialog.setOnShown(event -> {
            dialog.getDialogPane().getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
            nameField.requestFocus();
            nameField.selectAll();
        });

        dialog.showAndWait().ifPresent(value -> {
            String name = value.strip();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
                showDriveAlert(Alert.AlertType.ERROR, "Invalid name", "Enter a valid file or folder name.");
                return;
            }
            Path destination = entry.resolveSibling(name);
            if (Files.exists(destination)) {
                showDriveAlert(Alert.AlertType.ERROR, "Name already exists", "An item with that name is already in this folder.");
                return;
            }
            try {
                Files.move(entry, destination);
                renderDrivePage(content, true, entry.getParent());
            } catch (IOException error) {
                showDriveAlert(Alert.AlertType.ERROR, "Could not rename item", error.getMessage());
            }
        });
    }

    private HBox driveDialogTitleBar(Dialog<?> dialog, String text, String iconPath, boolean danger) {
        SVGPath icon = driveMenuGlyph(iconPath);
        if (danger) icon.getStyleClass().add("drive-delete-glyph");
        Label title = new Label(text);
        title.getStyleClass().add("drive-rename-title");
        Button close = new Button("×");
        close.getStyleClass().add("drive-dialog-close");
        close.setAccessibleText("Close");
        close.setOnAction(event -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(9, icon, title, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("drive-dialog-titlebar");
        double[] dragOffset = new double[2];
        bar.setOnMousePressed(event -> {
            if (event.getTarget() == close) return;
            javafx.stage.Window window = dialog.getDialogPane().getScene().getWindow();
            dragOffset[0] = event.getScreenX() - window.getX();
            dragOffset[1] = event.getScreenY() - window.getY();
        });
        bar.setOnMouseDragged(event -> {
            if (event.getTarget() == close) return;
            javafx.stage.Window window = dialog.getDialogPane().getScene().getWindow();
            window.setX(event.getScreenX() - dragOffset[0]);
            window.setY(event.getScreenY() - dragOffset[1]);
        });
        return bar;
    }

    private void deleteDriveEntry(Path entry, VBox content) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Delete from My files");
        confirmation.setHeaderText("Delete “" + entry.getFileName() + "”?");
        confirmation.setContentText(Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? "This folder and everything inside it will be permanently deleted from this device."
                : "This file will be permanently deleted from this device.");
        confirmation.initOwner(getScene().getWindow());
        confirmation.initStyle(StageStyle.TRANSPARENT);
        confirmation.getDialogPane().getStyleClass().add("drive-delete-dialog");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
        SVGPath deleteGlyph = driveMenuGlyph("M3 6h18 M8 6V4h8v2 M6 6l1 15h10l1-15");
        deleteGlyph.getStyleClass().add("drive-delete-glyph");
        confirmation.setGraphic(deleteGlyph);
        Button deleteButton = (Button) confirmation.getDialogPane().lookupButton(ButtonType.OK);
        deleteButton.setText("Delete");
        deleteButton.getStyleClass().add("drive-dialog-danger");
        confirmation.setOnShown(event -> confirmation.getDialogPane().getScene().setFill(javafx.scene.paint.Color.TRANSPARENT));
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> {
            try {
                deleteDrivePath(entry);
                renderDrivePage(content, true, entry.getParent());
            } catch (IOException error) {
                showDriveAlert(Alert.AlertType.ERROR, "Could not delete item", error.getMessage());
            }
        });
    }

    private static void deleteDrivePath(Path entry) throws IOException {
        if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(entry, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.delete(entry);
        }
    }

    private void shareDriveEntry(Path entry) {
        if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            showDriveAlert(Alert.AlertType.INFORMATION, "Share folder", "Folder sharing is not available yet. Open the folder and share its files one at a time.");
            return;
        }
        if (allContacts.isEmpty()) {
            showDriveAlert(Alert.AlertType.INFORMATION, "No contacts yet", "Add a contact before sharing a file.");
            return;
        }
        Dialog<ContactStore.Contact> dialog = new Dialog<>();
        dialog.setTitle("Share file");
        dialog.initOwner(getScene().getWindow());
        dialog.initStyle(StageStyle.TRANSPARENT);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("drive-share-dialog");
        pane.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());

        HBox titleBar = driveDialogTitleBar(dialog, "Share file", "M18 8a3 3 0 1 0-2.9-3.8L8.6 10.7 M8.6 13.3l6.8 4.4 M9 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M21 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M21 19a3 3 0 1 1-6 0 3 3 0 0 1 6 0", false);
        Label fileName = new Label(entry.getFileName().toString());
        fileName.getStyleClass().add("drive-share-file-name");
        fileName.setMaxWidth(Double.MAX_VALUE);
        fileName.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label prompt = new Label("Choose a contact to send this file to");
        prompt.getStyleClass().add("drive-rename-prompt");
        TextField search = new TextField();
        search.setPromptText("Search contacts");
        search.getStyleClass().add("drive-rename-field");

        FilteredList<ContactStore.Contact> visibleContacts = new FilteredList<>(FXCollections.observableArrayList(allContacts), contact -> true);
        search.textProperty().addListener((observable, before, after) -> {
            String query = after == null ? "" : after.strip().toLowerCase(Locale.ROOT);
            visibleContacts.setPredicate(contact -> query.isEmpty()
                    || contact.username().toLowerCase(Locale.ROOT).contains(query));
        });
        ListView<ContactStore.Contact> contactChoices = new ListView<>(visibleContacts);
        contactChoices.getStyleClass().add("drive-share-list");
        contactChoices.setPrefHeight(Math.min(300, Math.max(110, allContacts.size() * 56)));
        contactChoices.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ContactStore.Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                if (empty || contact == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label avatar = new Label(profileInitial(contact.username()));
                avatar.getStyleClass().add("drive-share-avatar");
                Label username = new Label(contact.username());
                username.getStyleClass().add("drive-share-username");
                Label address = new Label(shortAddress(contact.onion()));
                address.getStyleClass().add("drive-share-address");
                VBox details = new VBox(2, username, address);
                HBox row = new HBox(10, avatar, details);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        if (selected != null && allContacts.contains(selected)) contactChoices.getSelectionModel().select(selected);
        ButtonType continueType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, continueType);
        Button continueButton = (Button) pane.lookupButton(continueType);
        continueButton.getStyleClass().add("drive-dialog-primary");
        continueButton.setDisable(contactChoices.getSelectionModel().getSelectedItem() == null);
        contactChoices.getSelectionModel().selectedItemProperty().addListener((observable, before, after) ->
                continueButton.setDisable(after == null));
        VBox content = new VBox(10, titleBar, fileName, prompt, search, contactChoices);
        content.getStyleClass().add("drive-share-body");
        pane.setContent(content);
        pane.setPrefWidth(430);
        dialog.setResultConverter(button -> button == continueType ? contactChoices.getSelectionModel().getSelectedItem() : null);
        dialog.setOnShown(event -> {
            pane.getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
            if (contactChoices.getSelectionModel().getSelectedItem() == null) contactChoices.getSelectionModel().selectFirst();
            search.requestFocus();
        });
        dialog.showAndWait().ifPresent(contact -> shareDriveFileDirectly(contact, entry));
    }

    private void shareDriveFileDirectly(ContactStore.Contact contact, Path file) {
        status.setText("Sharing " + file.getFileName() + " directly with " + contact.username() + "…");
        driveWorkers.execute(() -> {
            try {
                manager.shareDriveFile(contact.link(), file, ignored -> {}, ignored -> {});
                Platform.runLater(() -> {
                    status.setText("Shared " + file.getFileName() + " with " + contact.username() + ".");
                    showDriveAlert(Alert.AlertType.INFORMATION, "File shared", "The file was delivered directly to "
                            + contact.username() + " and added to their Shared with me section.");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    status.setText("Could not share " + file.getFileName() + ".");
                    showDriveAlert(Alert.AlertType.ERROR, "Could not share file",
                            "The file could not be delivered to " + contact.username() + ". Make sure they are online and try again.\n\n"
                                    + (error.getMessage() == null ? "Transfer failed." : error.getMessage()));
                });
            }
        });
    }

    private static String shortAddress(String address) {
        if (address == null || address.isBlank()) return "";
        String value = address.replaceFirst("^https?://", "").replaceAll("/+$", "");
        return value.length() > 30 ? value.substring(0, 27) + "…" : value;
    }

    private void showDriveProperties(Path entry) {
        String details = "Name: " + entry.getFileName() + "\nType: "
                + (Files.isDirectory(entry) ? "Folder" : fileTypeLabel(entry)) + "\nLocation: " + entry.toAbsolutePath();
        if (Files.isRegularFile(entry)) details += "\nSize: " + formatBytes(fileSize(entry));
        try {
            details += "\nModified: " + Files.getLastModifiedTime(entry).toInstant()
                    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.ENGLISH));
        } catch (IOException ignored) { }
        showDriveAlert(Alert.AlertType.INFORMATION, "Properties", details);
    }

    private void showDriveAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.showAndWait();
    }

    private static long fileSize(Path file) {
        try { return Files.size(file); } catch (IOException ignored) { return 0; }
    }

    private void importIntoDrive(List<Path> sources, VBox content, Path currentDirectory, Button addFiles, Button addFolder,
                                 ProgressIndicator importing, Label feedback) {
        Path driveDirectory = AppData.directory().resolve("drive");
        addFiles.setDisable(true);
        addFolder.setDisable(true);
        importing.setManaged(true);
        importing.setVisible(true);
        feedback.setText("Copying into " + driveDirectory + "…");
        driveWorkers.execute(() -> {
            Exception failure = null;
            try {
                Files.createDirectories(driveDirectory);
                for (Path source : sources) copyIntoDrive(source, driveDirectory);
            } catch (Exception error) {
                failure = error;
            }
            Exception result = failure;
            Platform.runLater(() -> {
                if (content.getScene() == null) return;
                if (result == null) {
                    renderDrivePage(content, true, currentDirectory);
                } else {
                    addFiles.setDisable(false);
                    addFolder.setDisable(false);
                    importing.setVisible(false);
                    importing.setManaged(false);
                    feedback.setText("Could not add the selected item: " + result.getMessage());
                }
            });
        });
    }

    private static void copyIntoDrive(Path source, Path driveDirectory) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource)) throw new IOException("The selected item no longer exists.");
        if (Files.isDirectory(normalizedSource)) {
            Path normalizedDrive = driveDirectory.toAbsolutePath().normalize();
            if (normalizedDrive.startsWith(normalizedSource)) {
                throw new IOException("Choose a folder that does not contain the app's Drive folder.");
            }
            Path destination = uniqueDrivePath(driveDirectory, normalizedSource.getFileName().toString());
            Files.walkFileTree(normalizedSource, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(destination.resolve(normalizedSource.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!Files.isSymbolicLink(file)) {
                        Path target = destination.resolve(normalizedSource.relativize(file));
                        Files.copy(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } else if (Files.isRegularFile(normalizedSource) && !Files.isSymbolicLink(normalizedSource)) {
            Files.copy(normalizedSource, uniqueDrivePath(driveDirectory, normalizedSource.getFileName().toString()));
        } else {
            throw new IOException("Only regular files and folders can be added.");
        }
    }

    private static Path uniqueDrivePath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) return candidate;
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int suffix = 2; ; suffix++) {
            candidate = directory.resolve(base + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private void searchConversation() {
        if (selected == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Search conversation");
        dialog.setHeaderText("Search messages with " + selected.username());
        dialog.setContentText("Search text:");
        dialog.showAndWait().filter(query -> !query.isBlank()).ifPresent(query -> {
            String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < transcript.getItems().size(); i++) {
                MessageStore.Message message = transcript.getItems().get(i);
                String body = message.body() == null ? "" : message.body();
                String fileName = message.attachmentName() == null ? "" : message.attachmentName();
                if (body.toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || fileName.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    transcript.scrollTo(i);
                    transcript.getSelectionModel().select(i);
                    return;
                }
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "No matching messages were found.", ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }

    private static SVGPath plusGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M11 2h2v9h9v2h-9v9h-2v-9H2v-2h9z");
        icon.getStyleClass().add("plus-glyph");
        return icon;
    }

    private static SVGPath chatsGlyph() {
        SVGPath icon = new SVGPath();
        icon.setContent("M4 4h16v12H9l-5 4V4zm4 4v2h8V8H8zm0 4v2h5v-2H8z");
        icon.getStyleClass().add("chats-glyph");
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
        private final Label dateSeparator = new Label();
        private final HBox dateRow = new HBox(dateSeparator);
        private final VBox messageLayout = new VBox(dateRow);

        private MessageCell() {
            dateSeparator.getStyleClass().add("chat-date-pill");
            dateRow.getStyleClass().add("chat-date-row");
            dateRow.setAlignment(Pos.CENTER);
            var dateText = Bindings.createStringBinding(this::dateSeparatorText,
                    indexProperty(), transcript.getItems(), transcriptToday);
            dateSeparator.textProperty().bind(dateText);
            dateRow.visibleProperty().bind(dateText.isNotEmpty());
            dateRow.managedProperty().bind(dateRow.visibleProperty());
        }

        private String dateSeparatorText() {
            int index = getIndex();
            List<MessageStore.Message> messages = transcript.getItems();
            if (index < 0 || index >= messages.size()) return "";
            LocalDate date = messageDate(messages.get(index));
            return index == 0 || !date.equals(messageDate(messages.get(index - 1))) ? formatChatDate(date) : "";
        }

        @Override protected void updateItem(MessageStore.Message message, boolean empty) {
            super.updateItem(message, empty);
            setContextMenu(null);
            if (empty || message == null) { setText(null); setGraphic(null); return; }
            if (message.attachmentName() != null) {
                boolean voice = isVoiceMessage(message);
                Label duration = null;
                VBox rows = new VBox(5);
                rows.getStyleClass().addAll("message-card", message.outgoing() ? "outgoing" : "incoming");
                rows.setMaxWidth(680);
                if (voice) {
                    duration = new Label("–:––");
                    duration.getStyleClass().add("voice-note-duration");
                    rows.getStyleClass().add("voice-note-card");
                    rows.getChildren().add(createVoicePlayer(message, duration));
                    if (!message.outgoing() && message.attachmentPath() != null) {
                        MenuItem exportItem = new MenuItem("Export recording…");
                        exportItem.setOnAction(event -> export(message));
                        setContextMenu(new ContextMenu(exportItem));
                    }
                } else {
                    Label content = new Label("📎 " + message.attachmentName() + " (" + formatBytes(message.attachmentSize()) + ")");
                    content.setWrapText(true);
                    content.getStyleClass().add("message-content");
                    rows.getChildren().add(content);
                }
                if (message.body() != null && !message.body().isBlank()) {
                    rows.getChildren().add(messageText(message.body()));
                }
                ActiveTransfer transfer = activeTransfers.get(message.id());
                if (transfer != null) {
                    Region progressFill = new Region();
                    progressFill.getStyleClass().add("message-transfer-fill");
                    StackPane progressTrack = new StackPane(progressFill);
                    progressTrack.getStyleClass().add("message-transfer-track");
                    progressTrack.setMinHeight(9);
                    progressTrack.setPrefHeight(9);
                    progressTrack.setMaxHeight(9);
                    progressTrack.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    progressFill.setMinWidth(0);
                    progressFill.setPrefWidth(0);
                    progressFill.setMaxWidth(Region.USE_PREF_SIZE);
                    progressFill.prefWidthProperty().bind(progressTrack.widthProperty().multiply(transfer.progress));
                    progressFill.setMaxHeight(Double.MAX_VALUE);
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
                    HBox progressRow = new HBox(8, progressTrack, percent, rate, cancel);
                    progressRow.getStyleClass().add("message-transfer-row");
                    HBox.setHgrow(progressTrack, Priority.ALWAYS);
                    rows.getChildren().add(progressRow);
                }
                if (!voice && !message.outgoing()) {
                    Button exportButton = new Button("Export…");
                    exportButton.getStyleClass().add("export-button");
                    exportButton.setOnAction(event -> export(message));
                    rows.getChildren().add(new HBox(8, exportButton));
                }
                showMessage(rows, message, duration);
                return;
            }
            VBox rows = new VBox(4, messageText(message.body()));
            rows.getStyleClass().addAll("message-card", message.outgoing() ? "outgoing" : "incoming");
            rows.maxWidthProperty().bind(textBubbleMaxWidth);
            rows.setPadding(new Insets(6));
            showMessage(rows, message, null);
        }

        private void showMessage(VBox card, MessageStore.Message message, Label voiceDuration) {
            Instant sentAt = Instant.ofEpochMilli(message.timestamp());
            String fullTimestamp = TIME.format(sentAt);
            Label timestamp = new Label(MESSAGE_TIME.format(sentAt));
            timestamp.getStyleClass().add("message-timestamp");
            timestamp.setTooltip(new Tooltip(fullTimestamp));
            timestamp.setAccessibleText(fullTimestamp);
            timestamp.setMaxWidth(Double.MAX_VALUE);
            timestamp.setAlignment(message.outgoing() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            if (voiceDuration == null) {
                card.getChildren().add(timestamp);
            } else {
                HBox footer = message.outgoing() ? new HBox(8, voiceDuration, timestamp) : new HBox(8, timestamp, voiceDuration);
                footer.setAlignment(Pos.CENTER);
                HBox.setHgrow(timestamp, Priority.ALWAYS);
                card.getChildren().add(footer);
            }

            messageLayout.getChildren().setAll(dateRow, alignMessageCard(card, message));
            setText(null);
            setGraphic(messageLayout);
        }

        private HBox alignMessageCard(VBox card, MessageStore.Message message) {
            HBox bubbleRow = createReactionBubble(card, message);
            VBox messageGroup = new VBox(0, bubbleRow);
            messageGroup.setAlignment(message.outgoing() ? javafx.geometry.Pos.CENTER_RIGHT : javafx.geometry.Pos.CENTER_LEFT);
            messageGroup.setMaxWidth(680);
            HBox reactionBadges = createReactionBadges(message);
            if (reactionBadges != null) messageGroup.getChildren().add(reactionBadges);
            if (message.outgoing() && isLatestOutgoing(message.id())) {
                Label receipt = new Label();
                ActiveOutgoingMessage pendingMessage = activeOutgoingMessages.get(message.id());
                ActiveTransfer transfer = activeTransfers.get(message.id());
                if (pendingMessage != null) {
                    receipt.textProperty().bind(pendingMessage.status);
                    receipt.getStyleClass().add("delivery-status");
                } else if (transfer != null) {
                    receipt.textProperty().bind(Bindings.createStringBinding(
                            () -> transfer.sent.get() ? "✓ Sent" : "Sending…", transfer.sent));
                    receipt.getStyleClass().add("delivery-status");
                } else if (message.seenAt() > 0) {
                    receipt.setText("✓✓ Seen");
                    receipt.getStyleClass().addAll("delivery-status", "seen");
                } else {
                    receipt.setText("✓✓ Delivered");
                    receipt.getStyleClass().add("delivery-status");
                }
                messageGroup.getChildren().add(receipt);
            }
            HBox alignment = new HBox(messageGroup);
            alignment.setAlignment(message.outgoing() ? javafx.geometry.Pos.CENTER_RIGHT : javafx.geometry.Pos.CENTER_LEFT);
            alignment.setMaxWidth(Double.MAX_VALUE);
            alignment.prefWidthProperty().bind(transcriptRowWidth);
            return alignment;
        }

        private HBox createReactionBadges(MessageStore.Message message) {
            Map<String, ReactionCounts> reactions = messageReactions.getOrDefault(message.id(), Map.of());
            Map<String, Integer> pending = pendingMessageReactions.getOrDefault(message.id(), Map.of());
            if (reactions.isEmpty() && pending.isEmpty()) return null;

            HBox badges = new HBox(4);
            badges.getStyleClass().add("message-reaction-row");
            badges.setAlignment(message.outgoing() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            badges.setMaxWidth(Double.MAX_VALUE);
            badges.setTranslateY(-8);
            java.util.Set<String> emojis = new java.util.TreeSet<>(reactions.keySet());
            emojis.addAll(pending.keySet());
            for (String emoji : emojis) {
                ReactionCounts confirmed = reactions.getOrDefault(emoji, new ReactionCounts(0, 0));
                int mine = Math.max(0, confirmed.mine() + pending.getOrDefault(emoji, 0));
                int total = mine + confirmed.theirs();
                if (total == 0) continue;
                Label count = new Label(total > 1 ? Integer.toString(total) : "");
                HBox chip = new HBox(3, EmojiArtwork.icon(EmojiArtwork.imageFor(emoji), 16), count);
                chip.getStyleClass().add("message-reaction-chip");
                if (mine > 0) {
                    chip.getStyleClass().add("my-reaction");
                    Tooltip.install(chip, new Tooltip("Click to remove your reaction"));
                    chip.setCursor(javafx.scene.Cursor.HAND);
                    chip.setOnMouseClicked(event -> {
                        if (event.getButton() == MouseButton.PRIMARY) sendReactionChange(message, emoji, false);
                    });
                }
                badges.getChildren().add(chip);
            }
            if (badges.getChildren().isEmpty()) return null;
            return badges;
        }

        private HBox createReactionBubble(VBox card, MessageStore.Message message) {
            Button reactionButton = new Button();
            reactionButton.setGraphic(reactionGlyph());
            reactionButton.getStyleClass().add("message-reaction-trigger");
            reactionButton.setTooltip(new Tooltip("React to message"));
            reactionButton.setAccessibleText("React to message");
            reactionButton.setVisible(false);
            reactionButton.managedProperty().bind(reactionButton.visibleProperty());

            HBox bubbleRow = new HBox(6);
            bubbleRow.getStyleClass().add("message-bubble-row");
            bubbleRow.setAlignment(message.outgoing() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            bubbleRow.setMaxWidth(680);
            if (message.outgoing()) bubbleRow.getChildren().setAll(reactionButton, card);
            else bubbleRow.getChildren().setAll(card, reactionButton);
            bubbleRow.setOnMouseEntered(event -> reactionButton.setVisible(true));
            bubbleRow.setOnMouseExited(event -> {
                if (reactionMenu == null || !reactionMenu.isShowing()) reactionButton.setVisible(false);
            });
            reactionButton.setOnAction(event -> {
                reactionMenu = quickReactionMenu(message, reactionButton, bubbleRow);
                reactionMenu.show(reactionButton, Side.TOP, 0, -5);
            });
            return bubbleRow;
        }

        private ContextMenu reactionMenu;

        private ContextMenu quickReactionMenu(MessageStore.Message message, Button anchor, HBox bubbleRow) {
            ContextMenu menu = new ContextMenu();
            menu.getStyleClass().add("quick-reaction-menu");
            menu.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; "
                    + "-fx-background-radius: 22; -fx-border-color: transparent; "
                    + "-fx-padding: 0; -fx-effect: none;");
            HBox choices = new HBox(4);
            choices.getStyleClass().add("quick-reaction-bar");
            for (String emoji : List.of("👍", "❤️", "😂", "😮", "😢", "🙏")) {
                Button choice = new Button();
                choice.getStyleClass().add("quick-reaction-choice");
                choice.setGraphic(EmojiArtwork.icon(EmojiArtwork.imageFor(emoji), 21));
                choice.setAccessibleText(emoji);
                if (ownReactions(message.id()).containsKey(emoji)) choice.getStyleClass().add("selected");
                choice.setOnAction(event -> {
                    menu.hide();
                    sendReaction(message, emoji);
                });
                choices.getChildren().add(choice);
            }
            Button more = new Button("+");
            more.getStyleClass().addAll("quick-reaction-choice", "quick-reaction-more");
            more.setTooltip(new Tooltip("More emojis"));
            more.setOnAction(event -> {
                menu.hide();
                EmojiPicker picker = new EmojiPicker(emoji -> sendReaction(message, emoji));
                picker.setOnHidden(hidden -> {
                    if (!bubbleRow.isHover()) anchor.setVisible(false);
                });
                anchor.setVisible(true);
                picker.show(anchor, Side.TOP, 0, -5);
            });
            choices.getChildren().add(more);
            CustomMenuItem item = new CustomMenuItem(choices, false);
            item.getStyleClass().add("quick-reaction-item");
            item.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; "
                    + "-fx-background-radius: 22; -fx-padding: 0; -fx-effect: none;");
            menu.getItems().add(item);
            menu.setOnHidden(event -> {
                if (!bubbleRow.isHover()) anchor.setVisible(false);
            });
            menu.setOnShown(event -> {
                Node popupRoot = menu.getScene().getRoot();
                popupRoot.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; "
                        + "-fx-padding: 0; -fx-effect: none;");
                javafx.scene.shape.Rectangle roundedClip = new javafx.scene.shape.Rectangle(
                        popupRoot.getLayoutBounds().getWidth(), popupRoot.getLayoutBounds().getHeight());
                roundedClip.setArcWidth(44);
                roundedClip.setArcHeight(44);
                popupRoot.setClip(roundedClip);
            });
            return menu;
        }

        private SVGPath reactionGlyph() {
            SVGPath glyph = new SVGPath();
            glyph.setContent("M12 3 A9 9 0 1 1 11.99 3 Z M8.5 9 H8.6 M15.4 9 H15.5 M8 13 Q12 17 16 13");
            glyph.getStyleClass().add("message-reaction-glyph");
            return glyph;
        }
    }

    private static Region messageText(String value) {
        List<EmojiArtwork.Span> emojis = EmojiArtwork.findIn(value);
        if (emojis.isEmpty()) {
            Label text = new Label(value);
            text.setWrapText(true);
            text.setMinWidth(0);
            text.getStyleClass().add("message-content");
            return text;
        }

        TextFlow flow = new TextFlow();
        flow.setMinWidth(0);
        flow.setAccessibleText(value);
        flow.getStyleClass().add("emoji-message-content");
        int offset = 0;
        for (var emoji : emojis) {
            if (offset < emoji.start()) flow.getChildren().add(new Text(value.substring(offset, emoji.start())));
            flow.getChildren().add(EmojiArtwork.icon(emoji.image(), 17));
            offset = emoji.end();
        }
        if (offset < value.length()) flow.getChildren().add(new Text(value.substring(offset)));
        return flow;
    }

    private static boolean isVoiceMessage(MessageStore.Message message) {
        return message.attachmentName() != null && message.attachmentName().startsWith("voice-message-")
                && message.attachmentName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
    }

    private HBox createVoicePlayer(MessageStore.Message message, Label duration) {
        Button play = new Button();
        play.getStyleClass().add("voice-note-play");
        play.setGraphic(playGlyph());
        play.setAccessibleText("Play voice message");
        Tooltip playHint = new Tooltip("Loading voice recording…");
        play.setTooltip(playHint);
        play.setDisable(true);

        WaveformView waveform = new WaveformView(true);
        HBox.setHgrow(waveform, Priority.ALWAYS);
        String sender = message.outgoing() ? localUsername : selected == null ? null : selected.username();
        Label avatar = new Label(profileInitial(sender));
        avatar.getStyleClass().add("profile-bubble");
        Label mic = new Label();
        mic.setGraphic(microphoneGlyph());
        mic.getStyleClass().add("voice-note-mic");
        StackPane portrait = new StackPane(avatar, mic);
        StackPane.setAlignment(mic, Pos.BOTTOM_LEFT);
        HBox player = new HBox(8, play, waveform, portrait);
        player.getStyleClass().add("voice-note-player");

        if (message.attachmentPath() == null) {
            playHint.setText("Voice recording is unavailable on this device.");
            return player;
        }
        Path path = Path.of(message.attachmentPath());
        var isPlaying = Bindings.createBooleanBinding(() -> path.equals(activeVoicePath.get()) && voicePlaying.get(),
                activeVoicePath, voicePlaying);
        play.graphicProperty().bind(Bindings.when(isPlaying).then(pauseGlyph()).otherwise(playGlyph()));
        play.accessibleTextProperty().bind(Bindings.when(isPlaying)
                .then("Pause voice message").otherwise("Play voice message"));
        waveform.progressProperty().bind(Bindings.createDoubleBinding(
                () -> path.equals(activeVoicePath.get()) ? voicePlaybackProgress.get() : 0,
                activeVoicePath, voicePlaybackProgress));
        play.setOnAction(event -> playVoice(path));

        var preview = voiceNotes.computeIfAbsent(path, key -> CompletableFuture.supplyAsync(() -> {
            try { return VoiceNote.read(key); }
            catch (Exception unavailable) { return null; }
        }, voiceNoteReader));
        preview.thenAccept(note -> {
            Runnable showPreview = () -> {
                if (note == null) {
                    playHint.setText("Voice recording is unavailable on this device.");
                    voiceNotes.remove(path, preview);
                    return;
                }
                waveform.setPreview(note.levels());
                duration.setText(String.format(Locale.ROOT, "%d:%02d", note.seconds() / 60, note.seconds() % 60));
                playHint.textProperty().bind(play.accessibleTextProperty());
                play.setDisable(false);
            };
            if (Platform.isFxApplicationThread()) showPreview.run();
            else Platform.runLater(showPreview);
        });
        return player;
    }

    private void playVoice(Path path) {
        if (path.equals(activeVoicePath.get())) {
            if (activeVoiceClip == null) return;
            if (voicePlaying.get()) {
                activeVoiceClip.stop();
                voicePlaybackPoll.stop();
                voicePlaying.set(false);
                updateVoicePlayback();
            } else {
                startVoicePlayback();
            }
            return;
        }
        closeVoicePlayback();
        long request = voicePlaybackRequest;
        activeVoicePath.set(path);
        status.setText("Loading voice message…");
        Thread.ofPlatform().daemon().name("voice-playback").start(() -> {
            Clip clip = null;
            try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
                clip = AudioSystem.getClip();
                clip.open(audio);
                Clip ready = clip;
                Platform.runLater(() -> {
                    if (request != voicePlaybackRequest) {
                        ready.close();
                        return;
                    }
                    activeVoiceClip = ready;
                    ready.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP
                                && event.getFramePosition() >= ready.getFrameLength()) Platform.runLater(() -> {
                            if (activeVoiceClip == ready) closeVoicePlayback();
                        });
                    });
                    startVoicePlayback();
                });
            } catch (Exception e) {
                if (clip != null) try { clip.close(); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    if (request == voicePlaybackRequest) {
                        closeVoicePlayback();
                        status.setText("Could not play voice message: " + safe(e));
                    }
                });
            }
        });
    }

    private void startVoicePlayback() {
        activeVoiceClip.start();
        voicePlaying.set(true);
        voicePlaybackPoll.play();
        status.setText("Playing voice message…");
    }

    private void updateVoicePlayback() {
        double progress = (double) activeVoiceClip.getFramePosition() / activeVoiceClip.getFrameLength();
        if (progress >= 1) closeVoicePlayback();
        else voicePlaybackProgress.set(progress);
    }

    private void closeVoicePlayback() {
        voicePlaybackRequest++;
        voicePlaybackPoll.stop();
        Clip previous = activeVoiceClip;
        activeVoiceClip = null;
        activeVoicePath.set(null);
        voicePlaying.set(false);
        voicePlaybackProgress.set(0);
        if (previous != null) {
            previous.stop();
            previous.close();
        }
    }
}
