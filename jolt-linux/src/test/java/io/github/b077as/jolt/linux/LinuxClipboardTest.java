package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.contract.ClipboardContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The shared clipboard contract on Linux + GTK 3. Needs a display (X11 or Wayland); see {@code docker/linux-test/run-tests.sh}
 * and run it with {@code -Dtest=LinuxClipboardTest}. Wayland compositors may refuse clipboard access to a client that has no
 * focused window; if a run on a real compositor fails for that reason, that is a finding, not a test bug.
 *
 * <p>Klipper (KDE's clipboard manager) keeps its own copy and, with its default "prevent empty clipboard" setting, puts an
 * empty text entry back when a program clears the clipboard or withdraws a secret. That is the manager's doing, not ours, so
 * the tests that expect an empty clipboard, or that write a sensitive value,
 * are skipped while Klipper is on the session bus.
 */
@EnabledOnOs(OS.LINUX)
class LinuxClipboardTest extends ClipboardContract {

    private static final String KLIPPER = "a clipboard manager (Klipper) refills a cleared clipboard";

    private static final String KLIPPER_SECRET =
            "a clipboard manager (Klipper) does not record a sensitive value and may put its own last entry back";

    @Test
    @Override
    public void formatsDescribeWhatIsOnTheClipboard() {
        assumeFalse(klipperRuns(), KLIPPER);
        super.formatsDescribeWhatIsOnTheClipboard();
    }

    @Test
    @Override
    public void clearEmptiesTheClipboard() {
        assumeFalse(klipperRuns(), KLIPPER);
        super.clearEmptiesTheClipboard();
    }

    @Test
    @Override
    public void aSecretCanBeReadBackAtOnceAndIsRemovedAfterItsLifetime() {
        assumeFalse(klipperRuns(), KLIPPER);
        super.aSecretCanBeReadBackAtOnceAndIsRemovedAfterItsLifetime();
    }

    @Test
    @Override
    public void aValueWithoutALifetimeStays() {
        assumeFalse(klipperRuns(), KLIPPER_SECRET);
        super.aValueWithoutALifetimeStays();
    }

    @Test
    @Override
    public void aSecretIsLeftAloneIfSomethingElseWasCopiedMeanwhile() {
        assumeFalse(klipperRuns(), KLIPPER_SECRET);
        super.aSecretIsLeftAloneIfSomethingElseWasCopiedMeanwhile();
    }

    /** Whether {@code org.kde.klipper} has an owner on the session bus. False if that cannot be asked (no {@code gdbus}). */
    private static boolean klipperRuns() {
        try {
            Process process = new ProcessBuilder("gdbus", "call", "--session", "--dest", "org.freedesktop.DBus",
                    "--object-path", "/org/freedesktop/DBus", "--method", "org.freedesktop.DBus.NameHasOwner", "org.kde.klipper")
                    .redirectErrorStream(true).start();
            String answer = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return answer.contains("true");
        } catch (Exception e) {
            return false;
        }
    }
}
