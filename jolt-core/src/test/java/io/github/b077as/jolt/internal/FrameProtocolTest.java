package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameProtocolTest {

    private static final class Recorder implements FrameProtocol.Host {
        final List<String> calls = new ArrayList<>();

        @Override public void minimize() { calls.add("minimize"); }
        @Override public void toggleMaximize() { calls.add("maximize"); }
        @Override public void close() { calls.add("close"); }
        @Override public void beginMove() { calls.add("move"); }
        @Override public void beginResize(int edge) { calls.add("resize " + edge); }
        @Override public void reportState() { calls.add("state"); }
        @Override public void setMaximizeButton(int x, int y, int w, int h) { calls.add("maxbtn " + x + "," + y + "," + w + "," + h); }
        @Override public void clearMaximizeButton() { calls.add("maxbtn none"); }
        @Override public void showWindowMenu() { calls.add("menu"); }
        @Override public void setFullscreen(boolean on) { calls.add("fullscreen " + on); }
        @Override public void toggleFullscreen() { calls.add("fullscreen toggle"); }
    }

    private static List<String> run(String... commands) {
        Recorder host = new Recorder();
        for (String command : commands) {
            assertTrue(FrameProtocol.handle(FrameProtocol.PREFIX + command, host), command);
        }
        return host.calls;
    }

    @Test
    void everyCommandReachesTheHost() {
        assertEquals(List.of("minimize", "maximize", "close", "move", "state", "menu", "maxbtn none", "resize 4", "maxbtn 1,2,46,32",
                "fullscreen true", "fullscreen false", "fullscreen toggle"),
                run("minimize", "maximize", "close", "move", "state", "menu", "maxbtn:none", "resize:4", "maxbtn:1,2,46,32", "fullscreen:on", "fullscreen:off", "fullscreen:toggle"));
    }

    @Test
    void messagesWithoutThePrefixAreNotFrameMessages() {
        Recorder host = new Recorder();
        assertFalse(FrameProtocol.handle("close", host));
        assertFalse(FrameProtocol.isFrameMessage("jolt-frame:close"));
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void malformedAndOutOfRangeCommandsAreSwallowedButNeverActedOn() {
        assertEquals(List.of(), run("resize:0", "resize:9", "resize:", "resize:12", "maxbtn:", "maxbtn:1,2,3", "maxbtn:1,2,3,4,5",
                "maxbtn:a,b,c,d", "maxbtn:1,2,0,10", "maxbtn:1,2,10,-5", "maxbtn:1,2,10,999999999", "maxbtn:1,,10,10", "fullscreen:", "fullscreen:maybe", "nonsense", ""));
    }

    @Test
    void theMaximizeButtonRectangleIsParsedStrictly() {
        assertEquals(List.of(10, 20, 46, 32), java.util.Arrays.stream(FrameProtocol.parseRect("10,20,46,32")).boxed().toList());
        assertEquals(List.of(-5, 0, 46, 32), java.util.Arrays.stream(FrameProtocol.parseRect("-5,0,46,32")).boxed().toList());
        assertNull(FrameProtocol.parseRect("10,20,46"));
        assertNull(FrameProtocol.parseRect("10,20,0,32"));
        assertNull(FrameProtocol.parseRect("1e3,0,10,10"));
        assertNull(FrameProtocol.parseRect("100001,0,10,10"));
    }

    @Test
    void theScriptsTheWindowRunsAreSafeBeforeThePageHasAFrameScript() {
        assertEquals("window.__joltFrameApply && window.__joltFrameApply(true,false,true);", FrameProtocol.stateScript(true, false, true));
        assertEquals("window.__joltFrameHover && window.__joltFrameHover('max',true);", FrameProtocol.hoverScript("max", true));
        assertEquals("window.__joltFrameHover && window.__joltFrameHover(null,false);", FrameProtocol.hoverScript(null, false));
    }
}
