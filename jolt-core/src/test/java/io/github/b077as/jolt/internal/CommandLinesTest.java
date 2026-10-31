package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLinesTest {

    @Test
    void splitGroupsQuotedWordsAndDropsTheQuotes() {
        assertEquals(List.of("C:\\Program Files\\Parsel\\Parsel.exe", "--minimized"),
                CommandLines.split("\"C:\\Program Files\\Parsel\\Parsel.exe\" --minimized"));
        assertEquals(List.of("java", "-jar", "app.jar", ""), CommandLines.split("java -jar   app.jar \"\""));
        assertEquals(List.of(), CommandLines.split("   "));
    }

    @Test
    void windowsQuotesOnlyWhatNeedsIt() {
        assertEquals("C:\\Parsel.exe --minimized", CommandLines.windows(List.of("C:\\Parsel.exe", "--minimized")));
        assertEquals("\"C:\\Program Files\\Parsel.exe\" \"\"", CommandLines.windows(List.of("C:\\Program Files\\Parsel.exe", "")));
    }

    @Test
    void windowsEscapesQuotesAndTheBackslashesBeforeThem() {
        assertEquals("\"say \\\"hi\\\"\"", CommandLines.windows(List.of("say \"hi\"")));
        // a trailing backslash in a quoted argument must not swallow the closing quote
        assertEquals("\"C:\\My Dir\\\\\"", CommandLines.windows(List.of("C:\\My Dir\\")));
        assertEquals("\"a\\\\\\\"b c\"", CommandLines.windows(List.of("a\\\"b c")));
    }

    @Test
    void windowsOutputIsReadBackByTheSplitterForOrdinaryArguments() {
        List<String> command = List.of("C:\\Program Files\\Parsel\\Parsel.exe", "--minimized", "--profile=work account");
        assertEquals(command, CommandLines.split(CommandLines.windows(command)));
    }

    @Test
    void desktopExecLeavesPlainArgumentsAndQuotesTheRest() {
        assertEquals("/opt/parsel/bin/parsel --minimized", CommandLines.desktopExec(List.of("/opt/parsel/bin/parsel", "--minimized")));
        assertEquals("\"/home/me/My Apps/parsel\" --x", CommandLines.desktopExec(List.of("/home/me/My Apps/parsel", "--x")));
    }

    @Test
    void desktopExecEscapesWhatTheFormatTreatsSpecially() {
        // inside quotes ", `, $ and \ get a backslash; the .desktop value then doubles every backslash
        assertEquals("\"a\\\\$b\"", CommandLines.desktopExec(List.of("a$b")));
        assertEquals("\"a\\\\\\\\b\"", CommandLines.desktopExec(List.of("a\\b")));
        assertEquals("\"say \\\\\"hi\\\\\"\"", CommandLines.desktopExec(List.of("say \"hi\"")));
        // a percent sign would start a field code
        assertEquals("100%% done", CommandLines.desktopExec(List.of("100%", "done")).replace("100%% ", "100%% "));
        assertEquals("a%%b", CommandLines.desktopExec(List.of("a%b")));
    }
}
