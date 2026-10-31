package io.github.b077as.jolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceTest {

    private Path dir;

    @BeforeEach
    void createDirectory() throws IOException {
        dir = Files.createTempDirectory("jolt-single-");
    }

    @AfterEach
    void deleteDirectory() throws IOException {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private AppOptions options() {
        return AppOptions.of("io.github.b077as.jolt.test.single").withDataDirectory(dir);
    }

    @Test
    void theFirstCopyIsPrimaryAndALaterOneHandsItsArgumentsOverAndIsNot() throws Exception {
        CompletableFuture<List<String>> received = new CompletableFuture<>();
        try (SingleInstance first = SingleInstance.acquire(options(), List.of("--first"), received::complete)) {
            assertTrue(first.isPrimary());
            try (SingleInstance second = SingleInstance.acquire(options(), List.of("--minimized", "mail à é", ""), args -> {
                throw new AssertionError("only the primary is called");
            })) {
                assertFalse(second.isPrimary());
            }
            assertEquals(List.of("--minimized", "mail à é", ""), received.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void everyLaterLaunchIsReportedAndClosingLetsTheNextCopyBecomePrimary() throws Exception {
        java.util.concurrent.BlockingQueue<List<String>> received = new java.util.concurrent.LinkedBlockingQueue<>();
        SingleInstance first = SingleInstance.acquire(options(), List.of(), received::add);
        assertTrue(first.isPrimary());
        for (int i = 0; i < 3; i++) {
            SingleInstance other = SingleInstance.acquire(options(), List.of("launch-" + i), a -> { });
            assertFalse(other.isPrimary());
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of("launch-" + i), received.poll(10, TimeUnit.SECONDS));
        }
        first.close();
        try (SingleInstance next = SingleInstance.acquire(options(), List.of(), a -> { })) {
            assertTrue(next.isPrimary(), "the lock and the socket must be free again after close");
        }
    }

    @Test
    void differentApplicationsDoNotShareALock() throws Exception {
        Path other = Files.createTempDirectory("jolt-single-other-");
        try (SingleInstance a = SingleInstance.acquire(options(), List.of(), x -> { });
             SingleInstance b = SingleInstance.acquire(
                     AppOptions.of("io.github.b077as.jolt.test.other").withDataDirectory(other), List.of(), x -> { })) {
            assertTrue(a.isPrimary());
            assertTrue(b.isPrimary());
        } finally {
            try (var files = Files.walk(other)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void aVeryLongDataDirectoryStillWorksBecauseTheSocketMovesToAShortPath() throws Exception {
        Path deep = dir;
        while (deep.toString().length() < 160) {
            deep = deep.resolve("a-rather-long-directory-name");
        }
        Files.createDirectories(deep);
        AppOptions longOptions = AppOptions.of("io.github.b077as.jolt.test.long").withDataDirectory(deep);
        CompletableFuture<List<String>> received = new CompletableFuture<>();
        try (SingleInstance first = SingleInstance.acquire(longOptions, List.of(), received::complete)) {
            assertTrue(first.isPrimary());
            assertFalse(SingleInstance.acquire(longOptions, List.of("x"), a -> { }).isPrimary());
            assertEquals(List.of("x"), received.get(10, TimeUnit.SECONDS));
        }
    }
}
