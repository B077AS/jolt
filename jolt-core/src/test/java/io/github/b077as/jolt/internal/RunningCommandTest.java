package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunningCommandTest {

    @Test
    void aJarRunIsRebuiltAsDashJar() {
        assertEquals(List.of("C:\\jdk\\bin\\javaw.exe", "-jar", "C:\\apps\\notes.jar"),
                RunningCommand.rebuild("C:\\jdk\\bin\\javaw.exe", "C:\\apps\\notes.jar --flag", "C:\\apps\\notes.jar"));
    }

    @Test
    void aClassRunIsRebuiltWithTheClassPathAndMainClass() {
        assertEquals(List.of("/usr/bin/java", "-cp", "a.jar:b.jar", "com.example.Main"),
                RunningCommand.rebuild("/usr/bin/java", "com.example.Main x y", "a.jar:b.jar"));
    }

    @Test
    void aNativeLauncherIsJustItsOwnPath() {
        assertEquals(List.of("C:\\Program Files\\Notes\\Notes.exe"),
                RunningCommand.rebuild("C:\\Program Files\\Notes\\Notes.exe", "whatever", "whatever"));
    }

    @Test
    void whatCannotBeWorkedOutGivesNothing() {
        assertEquals(List.of(), RunningCommand.rebuild(null, "x", "y"));
        assertEquals(List.of(), RunningCommand.rebuild("java", null, "y"));
        assertEquals(List.of(), RunningCommand.rebuild("java", "x", " "));
    }
}
