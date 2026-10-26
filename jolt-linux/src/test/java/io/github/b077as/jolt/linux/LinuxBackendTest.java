package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Runs the shared backend contract against Linux + WebKitGTK 4.1. Needs GTK 3 and WebKitGTK installed and a display
 * (X11, or Wayland; under CI use Xvfb or a headless compositor, see {@code docker/linux-test}).
 */
@EnabledOnOs(OS.LINUX)
class LinuxBackendTest extends WebWindowContract {}
