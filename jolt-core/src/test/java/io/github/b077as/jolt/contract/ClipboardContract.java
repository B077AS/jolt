package io.github.b077as.jolt.contract;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.Clipboard;
import io.github.b077as.jolt.ClipboardContent;
import io.github.b077as.jolt.internal.ClipboardData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every backend's clipboard must do, against the REAL system clipboard. The tests overwrite it; each one puts the user's
 * text back afterwards (other formats cannot be preserved). Needs a desktop session.
 */
public abstract class ClipboardContract {

    private static final ClipboardData.Image PICTURE = new ClipboardData.Image(3, 2, new byte[] {
            10, 20, 30, (byte) 255, 40, 50, 60, (byte) 255, 70, 80, 90, (byte) 255,
            (byte) 200, (byte) 150, 100, (byte) 255, 1, 2, 3, (byte) 255, 9, 8, 7, (byte) 255});

    /**
     * Runs {@code body} with the application's clipboard inside a running application and returns what it produced. The body
     * must not block: chain futures. The user's clipboard text is restored when it is done.
     */
    protected <T> T run(Function<Clipboard, CompletableFuture<T>> body) {
        CompletableFuture<T> result = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            Clipboard clipboard = app.clipboard();
            clipboard.readText().whenComplete((original, readFailure) -> {
                CompletableFuture<T> test;
                try {
                    test = body.apply(clipboard);
                } catch (Throwable t) {
                    test = CompletableFuture.failedFuture(t);
                }
                test.whenComplete((value, failure) -> {
                    CompletableFuture<Void> restored = original != null && original.isPresent()
                            ? clipboard.writeText(original.get()) : clipboard.clear();
                    restored.whenComplete((ignored, restoreFailure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else {
                            result.complete(value);
                        }
                        app.quit();
                    });
                });
            });
        });
        return result.orTimeout(1, TimeUnit.SECONDS).join();
    }

    protected static <T> CompletableFuture<T> after(long millis, java.util.function.Supplier<CompletableFuture<T>> next) {
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> next.get());
    }

    @Test
    public void textRoundTripsIncludingUnicodeAndLineBreaks() {
        String text = "héllo ☃ 日本語 😀\r\nsecond line\nthird";
        Optional<String> read = run(c -> c.writeText(text).thenCompose(v -> c.readText()));
        assertEquals(Optional.of(text), read);
    }

    @Test
    public void htmlRoundTripsWithItsPlainTextAlternative() {
        Object[] got = run(c -> c.write(ClipboardContent.html("<b>hi ☃ & co</b>", "hi plain"))
                .thenCompose(v -> c.readHtml())
                .thenCompose(html -> c.readText().thenApply(text -> new Object[] {html, text})));
        assertEquals(Optional.of("<b>hi ☃ & co</b>"), got[0]);
        assertEquals(Optional.of("hi plain"), got[1]);
    }

    @Test
    public void anImageRoundTripsAsAPngWithTheSamePixels() {
        byte[] png = ClipboardData.encodePng(PICTURE);
        Optional<byte[]> read = run(c -> c.write(ClipboardContent.image(png)).thenCompose(v -> c.readImage()));
        assertTrue(read.isPresent());
        ClipboardData.Image back = ClipboardData.decodePng(read.get());
        assertNotNull(back, "what comes back is a PNG we can decode");
        assertEquals(PICTURE.width(), back.width());
        assertEquals(PICTURE.height(), back.height());
        assertArrayEquals(PICTURE.rgba(), back.rgba());
    }

    @Test
    public void filesRoundTripAsTheSameAbsolutePaths() throws IOException {
        Path dir = Files.createTempDirectory("jolt-clip");
        Path a = Files.writeString(dir.resolve("a file.txt"), "a");
        Path b = Files.writeString(dir.resolve("ü#b.txt"), "b");
        try {
            List<Path> read = run(c -> c.write(ClipboardContent.files(List.of(a, b))).thenCompose(v -> c.readFiles()));
            assertEquals(List.of(a, b), read);
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void formatsDescribeWhatIsOnTheClipboard() {
        byte[] png = ClipboardData.encodePng(PICTURE);
        List<Set<Clipboard.Format>> seen = run(c -> c.writeText("x").thenCompose(v -> c.formats())
                .thenCompose(first -> c.write(ClipboardContent.html("<i>x</i>", "x").withImage(png)).thenCompose(v -> c.formats())
                        .thenCompose(second -> c.clear().thenCompose(v -> c.formats())
                                .thenApply(third -> List.of(first, second, third)))));
        assertEquals(Set.of(Clipboard.Format.TEXT), seen.get(0));
        assertEquals(Set.of(Clipboard.Format.TEXT, Clipboard.Format.HTML, Clipboard.Format.IMAGE), seen.get(1));
        assertEquals(Set.of(), seen.get(2));
    }

    @Test
    public void aWriteReplacesWhatWasThereBeforeInsteadOfAddingToIt() {
        byte[] png = ClipboardData.encodePng(PICTURE);
        Set<Clipboard.Format> formats = run(c -> c.write(ClipboardContent.image(png))
                .thenCompose(v -> c.writeText("only text now")).thenCompose(v -> c.formats()));
        assertEquals(Set.of(Clipboard.Format.TEXT), formats);
    }

    @Test
    public void lineBreaksComeBackExactlyAsWritten() {
        for (String text : List.of("a\r\nb", "a\nb", "a\rb", "a\r\n\r\nb\n", "\r\n")) {
            Optional<String> read = run(c -> c.writeText(text).thenCompose(v -> c.readText()));
            assertEquals(Optional.of(text), read, "line breaks changed in " + text.replace("\r", "\\r").replace("\n", "\\n"));
        }
    }

    @Test
    public void clearEmptiesTheClipboard() {
        Optional<String> read = run(c -> c.writeText("gone soon").thenCompose(v -> c.clear()).thenCompose(v -> c.readText()));
        assertEquals(Optional.empty(), read);
    }

    @Test
    public void readingWhatIsNotThereGivesEmptyResultsNotErrors() {
        Object[] got = run(c -> c.writeText("just text").thenCompose(v -> c.readHtml())
                .thenCompose(html -> c.readImage().thenCompose(image -> c.readFiles()
                        .thenApply(files -> new Object[] {html, image, files}))));
        assertEquals(Optional.empty(), got[0]);
        assertEquals(Optional.empty(), got[1]);
        assertEquals(List.of(), got[2]);
    }

    @Test
    public void aChangeListenerHearsOurWritesAndStopsAfterItIsClosed() {
        List<Set<Clipboard.Format>> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        Integer afterClose = run(c -> {
            CompletableFuture<Void> first = new CompletableFuture<>();
            Clipboard.Subscription subscription = c.onChange(formats -> {
                heard.add(formats);
                first.complete(null);
            });
            return c.writeText("change me").thenCompose(v -> first)
                    .thenCompose(v -> {
                        subscription.close();
                        int before = heard.size();
                        return c.writeText("not heard").thenCompose(w -> after(700, () -> CompletableFuture.completedFuture(heard.size() - before)));
                    });
        });
        assertFalse(heard.isEmpty(), "the listener was called");
        assertTrue(heard.get(0).contains(Clipboard.Format.TEXT));
        assertEquals(0, afterClose, "nothing is delivered after close");
    }

    @Test
    public void aSecretCanBeReadBackAtOnceAndIsRemovedAfterItsLifetime() {
        Object[] got = run(c -> c.write(ClipboardContent.secret("s3cret-value", Duration.ofMillis(600)))
                .thenCompose(v -> c.readText())
                .thenCompose(now -> after(1800, () -> c.readText()).thenApply(later -> new Object[] {now, later})));
        assertEquals(Optional.of("s3cret-value"), got[0]);
        assertEquals(Optional.empty(), got[1], "the secret was taken off the clipboard");
    }

    @Test
    public void aSecretIsLeftAloneIfSomethingElseWasCopiedMeanwhile() {
        Optional<String> later = run(c -> c.write(ClipboardContent.secret("s3cret-value", Duration.ofMillis(600)))
                .thenCompose(v -> c.writeText("the user copied this"))
                .thenCompose(v -> after(1800, () -> c.readText())));
        assertEquals(Optional.of("the user copied this"), later);
    }

    @Test
    public void aValueWithoutALifetimeStays() {
        Optional<String> later = run(c -> c.write(ClipboardContent.text("stays").withSensitive(true))
                .thenCompose(v -> after(900, () -> c.readText())));
        assertEquals(Optional.of("stays"), later);
    }
}
