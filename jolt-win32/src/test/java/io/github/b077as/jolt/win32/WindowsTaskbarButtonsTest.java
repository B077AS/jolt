package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.TaskbarButton;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thumbnail toolbar under a window's taskbar preview. Windows' answer to adding and updating the buttons is checked (S_OK), and
 * presses are simulated by sending the {@code WM_COMMAND} Explorer sends. <b>How the strip looks, and that the buttons really appear
 * above the taskbar icon, needs a person pointing at the taskbar and is not tested here.</b>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsTaskbarButtonsTest {

    private static byte[] icon(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 3; y < 13; y++) {
            for (int x = 3; x < 13; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void press(WindowsWindow window, int id) {
        Win32.sendMessage(window.hwnd(), Win32.WM_COMMAND, ((long) WindowsTaskbarButtons.THBN_CLICKED << 16) | id, 0);
    }

    @Test
    void buttonsAreAddedUpdatedAndPressesReachTheirCallbacks() throws Exception {
        byte[] red = icon(0xCC0000), green = icon(0x00AA00);
        List<String> log = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(40, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.setTaskbarButtons(List.of(
                    TaskbarButton.of("Mute", red, () -> log.add("mute")),
                    TaskbarButton.of("Leave", green, () -> log.add("leave")).withDismissOnClick(true),
                    TaskbarButton.of("Third", red, () -> log.add("third")).withEnabled(false)));
            // Windows tells the window its taskbar button exists once it is shown; the buttons are added then
            WebWindowContract.eventually(() -> window.taskbarButtonsResult() != Integer.MIN_VALUE, 10_000).thenAccept(added -> app.dispatch(() -> {
                log.add("added " + added + " hr=" + window.taskbarButtonsResult());
                press(window, 0);
                press(window, 1);
                press(window, 2);   // a press on a disabled button never comes from Windows, but the id is ours: it runs
                press(window, 6);   // an id we do not have
                Win32.sendMessage(window.hwnd(), Win32.WM_COMMAND, 1, 0);                                   // a menu command, not a press
                Win32.sendMessage(window.hwnd(), Win32.WM_COMMAND, ((long) 0x1234 << 16) | 0, 0);          // another notification code
                // fewer buttons, new icons: an update of the same strip
                window.setTaskbarButtons(List.of(TaskbarButton.of("Mute", green, () -> log.add("mute2")).withEnabled(false)));
                app.dispatch(() -> {
                    log.add("updated hr=" + window.taskbarButtonsResult());
                    press(window, 0);
                    press(window, 1);   // hidden now: no callback
                    window.close();
                });
            }));
        });
        assertEquals(List.of("added true hr=0", "mute", "leave", "third", "updated hr=0", "mute2"), log);
    }

    @Test
    void moreButtonsThanWindowsAllowOrThanTheFirstListHadAreRefusedAtOnce() {
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            byte[] icon = new byte[] {1};
            Runnable nothing = () -> { };
            List<TaskbarButton> eight = java.util.Collections.nCopies(TaskbarButton.MAX_BUTTONS + 1, TaskbarButton.of("x", icon, nothing));
            assertThrows(IllegalArgumentException.class, () -> window.setTaskbarButtons(eight));
            window.setTaskbarButtons(List.of(TaskbarButton.of("a", icon, nothing), TaskbarButton.of("b", icon, nothing)));
            assertThrows(IllegalArgumentException.class, () -> window.setTaskbarButtons(List.of(
                    TaskbarButton.of("a", icon, nothing), TaskbarButton.of("b", icon, nothing), TaskbarButton.of("c", icon, nothing))));
            assertTrue(window.supportsTaskbarButtons());
            window.setTaskbarButtons(List.of()); // an empty list is always fine
            window.close();
        });
    }

    @Test
    void aTooltipLongerThanWindowsAcceptsIsRejectedWhenTheButtonIsMade() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskbarButton.of("t".repeat(TaskbarButton.MAX_TOOLTIP + 1), new byte[] {1}, () -> { }));
        TaskbarButton.of("t".repeat(TaskbarButton.MAX_TOOLTIP), new byte[] {1}, () -> { });
    }
}
