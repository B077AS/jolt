package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.contract.ClipboardContract;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The shared clipboard contract on Linux + GTK 3. Needs a display (X11 or Wayland); see {@code docker/linux-test/run-tests.sh}
 * and run it with {@code -Dtest=LinuxClipboardTest}. Wayland compositors may refuse clipboard access to a client that has no
 * focused window; if a run on a real compositor fails for that reason, that is a finding, not a test bug.
 */
@EnabledOnOs(OS.LINUX)
class LinuxClipboardTest extends ClipboardContract {}
