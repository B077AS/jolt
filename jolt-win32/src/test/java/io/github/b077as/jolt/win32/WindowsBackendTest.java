package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Runs the shared backend contract against Windows + WebView2. Needs a desktop session and the WebView2 runtime. */
@EnabledOnOs(OS.WINDOWS)
class WindowsBackendTest extends WebWindowContract {}
