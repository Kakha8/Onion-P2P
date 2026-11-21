package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.drive.FolderAccess;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SharedFolderMenuTest {
    @TempDir Path temp;

    private static void fx(Runnable action) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(5, TimeUnit.SECONDS);
    }

    private static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> ready.complete(null)); }
        catch (IllegalStateException running) { ready.complete(null); }
        ready.get(5, TimeUnit.SECONDS);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    @Test @Timeout(20)
    void folderAndEntryDownloadsAreMenuOptionsAlongsideProperties() throws Exception {
        toolkit();
        String previous = System.getProperty("onion.dataDir"); System.setProperty("onion.dataDir", temp.toString());
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, ignored -> {});
             var workers = Executors.newSingleThreadExecutor()) {
            fx(() -> {
                try {
                    var share = new SharedDriveStore.SharedFile(UUID.randomUUID().toString(), "owner", "Documents", null, -1, 0);
                    var browser = new SharedFolderBrowser(null, manager, share, "Alice", workers, () -> {});
                    var dialog = (Dialog<?>) field(browser, "dialog");
                    assertTrue(dialog.getDialogPane().getContent().lookupAll(".button").stream()
                            .map(node -> ((Button) node).getText()).noneMatch(text -> text != null && text.contains("Download")));
                    var menuMethod = SharedFolderBrowser.class.getDeclaredMethod("entryMenu", FolderAccess.Entry.class);
                    menuMethod.setAccessible(true);
                    for (var entry : new FolderAccess.Entry[]{null,
                            new FolderAccess.Entry("notes.txt", false, 512), new FolderAccess.Entry("Nested", true, -1)}) {
                        ContextMenu menu = (ContextMenu) menuMethod.invoke(browser, entry);
                        List<MenuItem> options = menu.getItems().stream().filter(item -> !(item instanceof SeparatorMenuItem)).toList();
                        assertEquals(entry != null && entry.folder() ? List.of("Open", "Download", "Download to…", "Properties") : List.of("Download", "Download to…", "Properties"),
                                options.stream().map(MenuItem::getText).toList());
                        assertTrue(options.stream().allMatch(item -> item.getGraphic() != null));
                        assertTrue(menu.getStyleClass().contains("drive-entry-menu"));
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }

    @Test @Timeout(20)
    void revokedRootClearsCachedRowsWithoutTryingAnOfflineOwner() throws Exception {
        toolkit();
        String previous = System.getProperty("onion.dataDir"); System.setProperty("onion.dataDir", temp.toString());
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, ignored -> {});
             var workers = Executors.newSingleThreadExecutor()) {
            SharedFolderBrowser[] browser = new SharedFolderBrowser[1];
            fx(() -> {
                try {
                    var share = new SharedDriveStore.SharedFile(UUID.randomUUID().toString(), "owner", "Documents", null, -1, 0);
                    browser[0] = new SharedFolderBrowser(null, manager, share, "Alice", workers, () -> {});
                    var entry = new FolderAccess.Entry("deleted.txt", false, 512);
                    ((ListView<FolderAccess.Entry>) field(browser[0], "files")).getItems().add(entry);
                    ((Map<String, List<FolderAccess.Entry>>) field(browser[0], "cache")).put("", List.of(entry));
                    var load = SharedFolderBrowser.class.getDeclaredMethod("load", String.class, boolean.class);
                    load.setAccessible(true); load.invoke(browser[0], "", true);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            // The serialized worker completes before this marker; its FX update is
            // queued first, so the following FX check observes the revocation.
            workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
            fx(() -> {
                try {
                    assertTrue(((ListView<?>) field(browser[0], "files")).getItems().isEmpty());
                    assertTrue(((Map<?, ?>) field(browser[0], "cache")).isEmpty());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }
}
