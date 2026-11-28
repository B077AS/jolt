package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.SessionEndReason;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.LifecycleContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lifecycle contract on Linux + WebKitGTK, and that a terminated process gets to hear of it. */
@EnabledOnOs(OS.LINUX)
class LinuxLifecycleTest extends LifecycleContract {

    @Override
    protected void crashPageProcess(WebWindow window) {
        ((LinuxWindow) window).crashPageProcess();
    }

    @Override
    protected void clickCloseButton(WebWindow window) {
        Gtk.windowClose(((LinuxWindow) window).nativeWindow());
    }

    /** The program the termination test runs: an application that notes what it is told in a file. */
    public static final class Waiting {
        public static void main(String[] args) throws IOException {
            Path log = Path.of(args[0]);
            Application app = Application.create(AppOptions.of("io.github.b077as.jolt.test").withDataDirectory(Path.of(args[1])));
            try {
                app.run(() -> {
                    WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
                    window.onClosed(() -> note(log, "closed"));
                    app.onSessionEnd(reason -> note(log, "ended " + reason + " " + app.isUiThread()));
                    window.onLoad(load -> note(log, "ready"));
                    window.loadHtml("<p>waiting</p>");
                });
            } finally {
                app.close();
            }
        }

        private static synchronized void note(Path log, String line) {
            try {
                Files.writeString(log, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    @Test
    void sigtermTellsTheApplicationAndClosesItsWindows(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // surefire may hide the real class path in a manifest jar; either way this property holds what the child needs
        Process child = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Waiting.class.getName(), log.toString(), directory.resolve("data").toString())
                .inheritIO().start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!(Files.exists(log) && Files.readString(log).contains("ready"))) {
                assertTrue(child.isAlive() && System.nanoTime() < deadline, "the child application did not start");
                Thread.sleep(100);
            }
            child.destroy(); // SIGTERM
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "the child did not end");
            assertEquals(List.of("ready", "ended " + SessionEndReason.TERMINATION + " true", "closed"), Files.readAllLines(log));
        } finally {
            child.destroyForcibly();
        }
    }
}
