package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JumpItem;
import io.github.b077as.jolt.JumpList;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jump list under the taskbar icon. Windows' answer to committing the list is checked (S_OK) and the entries handed to it are
 * read back from our side; <b>what the menu looks like, and that the entries really appear on the icon, needs a person right-clicking
 * the taskbar and is not tested here.</b>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJumpListTest {

    /** A PNG header is enough for the icon file to be made; Windows is not asked to draw it here. */
    private static byte[] png() {
        return WebWindowContract.samplePng();
    }

    @Test
    void tasksAndCategoriesAreCommittedAndAWindowTakesTheApplicationsId() {
        List<String> log = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(30, application -> {
            WindowsApplication app = (WindowsApplication) application;
            WindowsWindow before = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            assertTrue(app.supportsJumpList());
            app.setJumpList(JumpList.builder()
                    .command(List.of("C:\\Program Files\\Example\\example.exe"))
                    .task(JumpItem.of("New incognito window", "--incognito").withDescription("Private browsing").withIcon(png()))
                    .task("Settings", "--settings", "a b")
                    .category("Recent projects", List.of(JumpItem.of("Parsel", "--open", "42")))
                    .build());
            app.dispatch(() -> {
                WindowsJumpList list = app.jumpListForTests();
                log.add("hr=" + list.lastResult);
                log.add("categories " + list.lastCategoryResult);
                log.addAll(list.lastEntries);
                // a window made after the first list is given the id when it is made
                WindowsWindow after = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(200, 200));
                log.add("id " + WindowsJumpList.setWindowAppId(before.hwnd(), "com.example.jumplist") + " " + WindowsJumpList.setWindowAppId(after.hwnd(), "com.example.jumplist"));
                // an empty list deletes it
                app.setJumpList(JumpList.empty());
                app.dispatch(() -> {
                    log.add("cleared hr=" + app.jumpListForTests().lastResult + " " + app.jumpListForTests().lastEntries);
                    before.close();
                    after.close();
                });
            });
        });
        // Windows refuses categories (E_ACCESSDENIED) when the user turned off "Show recently opened items" in Settings; tasks still work
        boolean refused = log.get(1).equals("categories " + WindowsJumpList.E_ACCESSDENIED);
        List<String> expected = new java.util.ArrayList<>(List.of("hr=0", refused ? log.get(1) : "categories 0"));
        if (!refused) {
            expected.add("Recent projects/Parsel|--open 42");
        }
        expected.addAll(List.of("task:New incognito window|--incognito", "task:Settings|--settings a b", "id true true", "cleared hr=0 []"));
        assertEquals(expected, log);
    }

    @Test
    void anApplicationIdWindowsCannotUseGivesNoListAndNoCrash() {
        assertFalse(WindowsJumpList.validAppId("has space"));
        assertFalse(WindowsJumpList.validAppId("x".repeat(129)));
        assertTrue(WindowsJumpList.validAppId("com.example.app"));
    }

    @Test
    void theLimitsAreCheckedWhenTheListIsMade() {
        assertThrows(IllegalArgumentException.class, () -> JumpItem.of("  "));
        assertThrows(IllegalArgumentException.class, () -> JumpItem.of("two\nlines"));
        assertThrows(IllegalArgumentException.class, () -> new JumpList.Category("", List.of()));
        List<JumpItem> many = java.util.Collections.nCopies(JumpList.MAX_ITEMS + 1, JumpItem.of("x"));
        assertThrows(IllegalArgumentException.class, () -> JumpList.builder().category("c", many).build());
    }
}
