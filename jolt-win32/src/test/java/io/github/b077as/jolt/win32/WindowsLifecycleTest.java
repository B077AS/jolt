package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.SessionEndReason;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.LifecycleContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static io.github.b077as.jolt.contract.WebWindowContract.runApp;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The lifecycle contract on Windows + WebView2, and how Windows announces the end of a session. */
@EnabledOnOs(OS.WINDOWS)
class WindowsLifecycleTest extends LifecycleContract {

    @Override
    protected void crashPageProcess(WebWindow window) {
        ((WindowsWindow) window).crashPageProcess();
    }

    @Override
    protected void clickCloseButton(WebWindow window) {
        Win32.postMessage(((WindowsWindow) window).hwnd(), Win32.WM_CLOSE, 0, 0);
    }

    /** WebView2 (seen with runtime 158) gives no error status for a port nothing listens on. */
    @Override
    protected LoadFailure.Reason refusedConnection() {
        return LoadFailure.Reason.OTHER;
    }

    /** The messages Windows sends at sign-out, sent by hand to the window that listens for them. */
    @Test
    void theEndOfTheSessionIsReportedOnceAndOnlyWhenItHappens() {
        List<String> seen = new ArrayList<>();
        runApp(30, app -> {
            WindowsApplication windows = (WindowsApplication) app;
            app.createWindow(WindowOptions.defaults().withSize(300, 200).withVisible(false));
            app.onSessionEnd(reason -> seen.add("ended " + reason + " on ui " + app.isUiThread()));
            app.dispatch(() -> {
                long query = Win32.sendMessage(windows.sessionWindow(), Win32.WM_QUERYENDSESSION, 0, 0);
                seen.add("query " + query);
                Win32.sendMessage(windows.sessionWindow(), Win32.WM_ENDSESSION, 0, 0); // another program refused: nothing ends
                seen.add("cancelled");
                Win32.sendMessage(windows.sessionWindow(), Win32.WM_ENDSESSION, 1, 0);
                seen.add("sent");
                Win32.sendMessage(windows.sessionWindow(), Win32.WM_ENDSESSION, 1, 0);
                app.quit();
            });
        });
        assertEquals(List.of("query 1", "cancelled", "ended " + SessionEndReason.SIGN_OUT_OR_SHUTDOWN + " on ui true", "sent"), seen);
    }
}
