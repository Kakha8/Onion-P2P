package kakha.kudava.onionp2p;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Local emoji palette. The composer receives the original Unicode text. */
final class EmojiPicker extends ContextMenu {
    private static final int REGIONAL_INDICATOR_A = 0x1F1E6;
    private static final Map<String, String> EXTRA_REGION_NAMES = Map.of(
            "AC", "Ascension Island",
            "CP", "Clipperton Island",
            "CQ", "Sark",
            "DG", "Diego Garcia",
            "EA", "Ceuta and Melilla",
            "IC", "Canary Islands",
            "TA", "Tristan da Cunha",
            "XK", "Kosovo");
    private static final List<Category> CATEGORIES = List.of(
            category("Smileys & People",
                    "M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10 Z M7 8 H7.1 M12.9 8 H13 M6.5 12 Q10 16 13.5 12",
                    """
                    😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊
                    😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪
                    😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏
                    😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕
                    🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓
                    🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧
                    😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫
                    😤 😡 😠 🤬 😈 👿 💀 👻 👽 🤖 😺 🙀
                    👋 🤚 ✋ 🖐️ 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉
                    👍 👎 ✊ 👊 👏 🙌 🤝 🙏 💪 👀 👁️ 🫶
                    """),
            category("Animals & Nature",
                    "M4 16 C3 9 8 4 17 3 C18 12 13 17 6 17 M3 18 L13 8",
                    """
                    🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮
                    🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺
                    🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🦖 🐙 🐠
                    🐬 🐳 🦈 🐘 🦒 🦔 🌱 🌵 🌴 🌲 🍀 🍁
                    🌸 🌻 🌹 🌷 🍄 🌍 🌙 ⭐ ☀️ ☁️ 🌧️ ❄️
                    """),
            category("Food & Drink",
                    "M3 5 H14 V11 A4 4 0 0 1 10 15 H7 A4 4 0 0 1 3 11 Z M14 6 H16 A2 2 0 0 1 16 10 H14 M2 18 H17",
                    """
                    🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭
                    🍍 🥝 🥑 🍅 🌽 🥕 🥦 🍞 🧀 🍳 🥞 🥓
                    🍔 🍟 🍕 🌭 🥪 🌮 🍜 🍝 🍣 🍱 🥟 🍤
                    🍦 🍩 🍪 🎂 🍰 🍫 🍿 ☕ 🍵 🥤 🍺 🍷
                    """),
            category("Activities",
                    "M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10 Z M2 10 H18 M10 2 V18 M4.5 4.5 Q10 10 15.5 15.5 M15.5 4.5 Q10 10 4.5 15.5",
                    """
                    ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🏒
                    ⛳ 🛹 🛼 🛷 ⛸️ 🏂 🏄 🏊 🚴 🏆 🥇 🏅
                    🎮 🕹️ 🎲 ♟️ 🎯 🎳 🧩 🎨 🎭 🎤 🎸 🎹
                    """),
            category("Travel & Places",
                    "M2 14 V9 L4 4 H16 L18 9 V14 Z M2 9 H18 M4 14 V17 M16 14 V17 M5 11.5 H6 M14 11.5 H15",
                    """
                    🚗 🚕 🚌 🚎 🏎️ 🚓 🚑 🚒 🚚 🚜 🏍️ 🚲
                    🚇 🚆 🚄 🚋 ✈️ 🛫 🚁 🚀 🛸 🚢 ⛵ ⚓
                    🏠 🏢 🏫 🏥 🏰 🏟️ 🗼 🗽 ⛺ 🏕️ 🏔️ 🌋
                    🏖️ 🏝️ 🗺️ 🌆 🌃 🌉 🎢 🎡 🛣️ 🗻 🧭 🧳
                    """),
            category("Objects",
                    "M6 13 C6 11 4 10 4 7 A6 6 0 0 1 16 7 C16 10 14 11 14 13 V15 H6 Z M7 17 H13 M8 19 H12",
                    """
                    💡 🔦 🕯️ 🔋 🔌 📱 💻 ⌨️ 🖥️ 📷 🎥 📺
                    📻 🎧 🎙️ 📞 ⏰ ⌚ 📚 📖 📝 ✏️ 📌 📎
                    ✂️ 🗂️ 📁 📦 ✉️ 📬 🔑 🔒 🔓 🔍 🔧 🔨
                    💎 💰 💳 💸 🧲 🔔 🎁 🎈 🪄 🛡️ 🧪 🔬
                    """),
            category("Symbols",
                    "M4 4 H11 M7.5 4 V10 M15 3 V8 M18 3 H15 M15 8 H13 M4 15 H7 M5.5 13.5 V16.5 M11 18 L18 11",
                    """
                    ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 💕 💖
                    💗 💓 💞 💟 ❣️ 🔥 ✨ 💫 💥 💯 ✅ ❌
                    ⚠️ ❗ ❓ ❕ ❔ ♻️ ☮️ ☯️ ✝️ ☪️ ♾️ 🛑
                    ⬆️ ⬇️ ⬅️ ➡️ 🔄 🔀 ▶️ ⏸️ ⏹️ 🔴 🟢 🔵
                    """),
            flagsCategory());

    private record Emoji(String symbol, String name) {}
    private record Category(String name, String glyph, List<Emoji> emojis) {}

    private final Consumer<String> onSelection;
    private final TextField search = new TextField();
    private final Label heading = new Label();
    private final TilePane grid = new TilePane(2, 2);
    private final Label empty = new Label("No matching emojis");
    private final ScrollPane scroll;
    private Category activeCategory = CATEGORIES.getFirst();
    private boolean populated;

    EmojiPicker(Consumer<String> onSelection) {
        this.onSelection = onSelection;
        getStyleClass().add("emoji-picker");

        search.setPromptText("Search emoji");
        search.getStyleClass().add("emoji-search-field");
        search.textProperty().addListener((obs, previous, query) -> refresh());
        HBox searchBar = new HBox(8, glyph("M13 8 A5 5 0 1 1 3 8 A5 5 0 1 1 13 8 Z M12 12 L17 17",
                "emoji-search-glyph"), search);
        searchBar.getStyleClass().add("emoji-search");
        HBox.setHgrow(search, Priority.ALWAYS);

        heading.getStyleClass().add("emoji-picker-title");
        empty.getStyleClass().add("emoji-picker-empty");
        empty.managedProperty().bind(empty.visibleProperty());
        grid.setPrefColumns(12);
        grid.setAlignment(Pos.TOP_LEFT);
        VBox results = new VBox(6, heading, grid, empty);
        results.getStyleClass().add("emoji-picker-results");
        scroll = new ScrollPane(results);
        scroll.getStyleClass().add("emoji-picker-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(230);

        VBox panel = new VBox(9, createCategoryBar(), searchBar, scroll);
        panel.setPrefWidth(444);
        panel.getStyleClass().add("emoji-picker-panel");
        CustomMenuItem item = new CustomMenuItem(panel, false);
        item.getStyleClass().add("emoji-picker-item");
        getItems().add(item);
        setOnShowing(event -> {
            if (!populated) {
                populated = true;
                refresh();
            }
        });
        setOnShown(event -> Platform.runLater(() -> {
            if (isShowing()) search.requestFocus();
        }));
    }

    private HBox createCategoryBar() {
        HBox bar = new HBox(4);
        ToggleGroup group = new ToggleGroup();
        for (Category category : CATEGORIES) {
            ToggleButton tab = new ToggleButton();
            tab.setGraphic(glyph(category.glyph(), "emoji-category-glyph"));
            tab.setAccessibleText(category.name());
            tab.setTooltip(new Tooltip(category.name()));
            tab.setToggleGroup(group);
            tab.setSelected(category == activeCategory);
            tab.setMaxWidth(Double.MAX_VALUE);
            tab.getStyleClass().add("emoji-category");
            tab.setOnAction(event -> {
                tab.setSelected(true);
                activeCategory = category;
                if (search.getText().isEmpty()) refresh();
                else search.clear();
            });
            HBox.setHgrow(tab, Priority.ALWAYS);
            bar.getChildren().add(tab);
        }
        return bar;
    }

    private void refresh() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        heading.setText(query.isEmpty() ? activeCategory.name() : "Search results");
        List<Emoji> matches = query.isEmpty() ? activeCategory.emojis() : CATEGORIES.stream()
                .flatMap(category -> category.emojis().stream())
                .filter(emoji -> emoji.symbol().contains(query) || emoji.name().contains(query))
                .toList();
        grid.getChildren().setAll(matches.stream().map(this::createChoice).toList());
        empty.setVisible(matches.isEmpty());
        scroll.setVvalue(0);
    }

    private Button createChoice(Emoji emoji) {
        Button button = new Button();
        button.getStyleClass().add("emoji-choice");
        button.setTooltip(new Tooltip(emoji.name()));
        button.setAccessibleText(emoji.name());
        Image image = EmojiArtwork.imageFor(emoji.symbol());
        if (image == null) {
            button.setText(emoji.symbol());
        } else {
            button.setGraphic(EmojiArtwork.icon(image, 25));
        }
        button.setOnAction(event -> {
            hide();
            onSelection.accept(emoji.symbol());
        });
        return button;
    }

    private static SVGPath glyph(String path, String styleClass) {
        SVGPath glyph = new SVGPath();
        glyph.setContent(path);
        glyph.getStyleClass().add(styleClass);
        return glyph;
    }

    private static Category category(String name, String glyph, String symbols) {
        return new Category(name, glyph, Arrays.stream(symbols.strip().split("\\s+"))
                .map(symbol -> new Emoji(symbol, emojiName(symbol))).toList());
    }

    private static Category flagsCategory() {
        List<Emoji> flags = new ArrayList<>(Stream.concat(
                        Arrays.stream(Locale.getISOCountries()), EXTRA_REGION_NAMES.keySet().stream())
                .map(EmojiPicker::flagSymbol)
                .map(symbol -> new Emoji(symbol, emojiName(symbol)))
                .toList());
        flags.add(new Emoji(subdivisionFlag("gbeng"), "england flag"));
        flags.add(new Emoji(subdivisionFlag("gbsct"), "scotland flag"));
        flags.add(new Emoji(subdivisionFlag("gbwls"), "wales flag"));
        flags.sort(Comparator.comparing(Emoji::name, Collator.getInstance(Locale.ENGLISH)));
        for (String symbol : List.of("🇪🇺", "🇺🇳", "🏁", "🚩", "🏴", "🏳️", "🏳️‍🌈")) {
            flags.add(new Emoji(symbol, emojiName(symbol)));
        }
        return new Category("Flags", "M4 18 V3 H11 L12 6 H17 V13 H10 L9 10 H4", List.copyOf(flags));
    }

    private static String flagSymbol(String country) {
        return Character.toString(REGIONAL_INDICATOR_A + country.charAt(0) - 'A')
                + Character.toString(REGIONAL_INDICATOR_A + country.charAt(1) - 'A');
    }

    private static String subdivisionFlag(String code) {
        StringBuilder flag = new StringBuilder("🏴");
        code.chars().forEach(letter -> flag.appendCodePoint(0xE0000 + letter));
        return flag.appendCodePoint(0xE007F).toString();
    }

    private static String emojiName(String symbol) {
        int[] points = symbol.codePoints().toArray();
        if (points.length == 2 && Arrays.stream(points).allMatch(
                cp -> cp >= REGIONAL_INDICATOR_A && cp < REGIONAL_INDICATOR_A + 26)) {
            String country = Character.toString('A' + points[0] - REGIONAL_INDICATOR_A)
                    + Character.toString('A' + points[1] - REGIONAL_INDICATOR_A);
            String name = EXTRA_REGION_NAMES.get(country);
            if (name == null) name = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
            return (name + " flag").toLowerCase(Locale.ROOT);
        }
        return switch (symbol) {
            case "😂" -> "laughing face with tears of joy";
            case "🤣" -> "rolling on the floor laughing";
            case "❤️" -> "red heart";
            case "👍" -> "thumbs up";
            case "👎" -> "thumbs down";
            case "☕" -> "coffee hot beverage";
            case "🎉" -> "party popper celebration";
            case "🏳️‍🌈" -> "rainbow pride flag";
            default -> Arrays.stream(points).filter(cp -> cp != 0xFE0F && cp != 0x200D)
                    .mapToObj(Character::getName).filter(Objects::nonNull)
                    .collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        };
    }

}
