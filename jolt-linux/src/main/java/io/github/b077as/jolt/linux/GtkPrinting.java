package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.PrintOptions;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Printing a page to a PDF file with WebKitGTK's print operation and GTK's built-in "Print to File" printer, without a dialog.
 * UI thread only. Kept apart from {@link Gtk} (like {@link GtkResources}) so that a system where something is missing loses only
 * this feature. <b>This code has been compiled but not run</b>: no Linux session was available when it was written.
 *
 * <p>The page size, orientation and margins are honoured; WebKit decides for itself about the scale, backgrounds and headers, so
 * {@link PrintOptions#scale()}, {@link PrintOptions#backgrounds()} and {@link PrintOptions#headerAndFooter()} are passed on only as
 * far as GTK's print settings carry them.
 */
final class GtkPrinting {

    private static final int GTK_UNIT_MM = 3;
    private static final int GTK_PAGE_ORIENTATION_PORTRAIT = 0, GTK_PAGE_ORIENTATION_LANDSCAPE = 1;

    private record Handles(
            MethodHandle operationNew, MethodHandle setPrintSettings, MethodHandle setPageSetup, MethodHandle print,
            MethodHandle settingsNew, MethodHandle settingsSet, MethodHandle settingsSetPrinter, MethodHandle settingsSetScale,
            MethodHandle pageSetupNew, MethodHandle paperSizeNewCustom, MethodHandle paperSizeFree, MethodHandle setPaperSize,
            MethodHandle setOrientation, MethodHandle setTopMargin, MethodHandle setBottomMargin, MethodHandle setLeftMargin,
            MethodHandle setRightMargin, MethodHandle objectUnref) {}

    /** {@code null} when this system lacks something. */
    private static final Handles H = load();
    private static final MemorySegment FINISHED = H == null ? null : stub("onFinished", MethodType.methodType(void.class,
            MemorySegment.class, MemorySegment.class), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MemorySegment FAILED = H == null ? null : stub("onFailed", MethodType.methodType(void.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    private GtkPrinting() {}

    static boolean available() {
        return H != null;
    }

    private static Handles load() {
        try {
            Arena libs = Arena.global();
            SymbolLookup gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", libs);
            SymbolLookup gtk = SymbolLookup.libraryLookup("libgtk-3.so.0", libs);
            SymbolLookup webkit = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", libs);
            FunctionDescriptor setMargin = FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_INT);
            return new Handles(
                    Native.downcall(webkit, "webkit_print_operation_new", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_print_operation_set_print_settings", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_print_operation_set_page_setup", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_print_operation_print", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gtk, "gtk_print_settings_new", FunctionDescriptor.of(ADDRESS)),
                    Native.downcall(gtk, "gtk_print_settings_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_print_settings_set_printer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_print_settings_set_scale", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE)),
                    Native.downcall(gtk, "gtk_page_setup_new", FunctionDescriptor.of(ADDRESS)),
                    Native.downcall(gtk, "gtk_paper_size_new_custom",
                            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT)),
                    Native.downcall(gtk, "gtk_paper_size_free", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gtk, "gtk_page_setup_set_paper_size", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_page_setup_set_orientation", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                    Native.downcall(gtk, "gtk_page_setup_set_top_margin", setMargin),
                    Native.downcall(gtk, "gtk_page_setup_set_bottom_margin", setMargin),
                    Native.downcall(gtk, "gtk_page_setup_set_left_margin", setMargin),
                    Native.downcall(gtk, "gtk_page_setup_set_right_margin", setMargin),
                    Native.downcall(gobject, "g_object_unref", FunctionDescriptor.ofVoid(ADDRESS)));
        } catch (RuntimeException | Error e) { // a missing library or symbol
            System.getLogger("io.github.b077as.jolt.linux").log(System.Logger.Level.WARNING,
                    "Printing to PDF is not available on this system: {0}", e.toString());
            return null;
        }
    }

    private static MemorySegment stub(String method, MethodType type, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(GtkPrinting.class, method, type);
            return Native.LINKER.upcallStub(Native.guarded(target, 0), descriptor, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A print operation under way: where its PDF is going and who is waiting for it. */
    private record Pending(CompletableFuture<byte[]> result, Path file) {}

    /**
     * Starts printing {@code webView} to a temporary PDF; {@code result} gets its bytes when WebKit reports that it is done.
     * Completes {@code result} exceptionally if it cannot start.
     */
    static void start(LinuxApplication app, MemorySegment webView, PrintOptions options, CompletableFuture<byte[]> result) {
        if (H == null) {
            result.completeExceptionally(new UnsupportedOperationException("this system's GTK or WebKitGTK cannot print to PDF"));
            return;
        }
        Path file;
        try {
            file = Files.createTempFile("jolt-print-", ".pdf");
        } catch (IOException e) {
            result.completeExceptionally(new JoltException("Printing to PDF failed: " + e.getMessage(), e));
            return;
        }
        MemorySegment operation = (MemorySegment) call(H.operationNew, webView);
        long id = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment settings = (MemorySegment) call(H.settingsNew);
            call(H.settingsSetPrinter, settings, Gtk.utf8(arena, "Print to File"));
            call(H.settingsSet, settings, Gtk.utf8(arena, "output-file-format"), Gtk.utf8(arena, "pdf"));
            call(H.settingsSet, settings, Gtk.utf8(arena, "output-uri"), Gtk.utf8(arena, file.toUri().toString()));
            call(H.settingsSetScale, settings, options.scale() * 100.0);
            MemorySegment paper = (MemorySegment) call(H.paperSizeNewCustom, Gtk.utf8(arena, "jolt"), Gtk.utf8(arena, "Jolt"),
                    options.paperWidthMm(), options.paperHeightMm(), GTK_UNIT_MM);
            MemorySegment setup = (MemorySegment) call(H.pageSetupNew);
            call(H.setPaperSize, setup, paper);
            call(H.setOrientation, setup, options.landscape() ? GTK_PAGE_ORIENTATION_LANDSCAPE : GTK_PAGE_ORIENTATION_PORTRAIT);
            // after the paper size, which resets the margins to the paper's own
            call(H.setTopMargin, setup, options.marginTopMm(), GTK_UNIT_MM);
            call(H.setBottomMargin, setup, options.marginBottomMm(), GTK_UNIT_MM);
            call(H.setLeftMargin, setup, options.marginLeftMm(), GTK_UNIT_MM);
            call(H.setRightMargin, setup, options.marginRightMm(), GTK_UNIT_MM);
            call(H.setPrintSettings, operation, settings);
            call(H.setPageSetup, operation, setup);
            call(H.paperSizeFree, paper);
            call(H.objectUnref, setup);     // the operation holds its own references
            call(H.objectUnref, settings);

            // Removed only by "finished", which follows "failed": it deletes the file and drops our reference to the operation
            id = Registry.add(new Pending(result, file));
            Gtk.connect(operation, "finished", FINISHED, MemorySegment.ofAddress(id));
            Gtk.connect(operation, "failed", FAILED, MemorySegment.ofAddress(id));
            call(H.print, operation);
        } catch (RuntimeException e) {
            if (id != 0) {
                Registry.remove(id);
            }
            deleteQuietly(file);
            call(H.objectUnref, operation);
            result.completeExceptionally(e instanceof JoltException ? e : new JoltException("Printing to PDF failed: " + e.getMessage(), e));
        }
        // the operation object stays alive until the print operation finishes; finished drops our reference
    }

    /** {@code failed}: the print operation stopped with an error. {@code finished} comes after it, so this only records the reason. */
    private static void onFailed(MemorySegment operation, MemorySegment gError, MemorySegment data) {
        Pending pending = Registry.get(data.address(), Pending.class);
        if (pending != null) {
            String message = gError.equals(MemorySegment.NULL) ? "unknown error" : Gtk.errorMessage(gError);
            pending.result().completeExceptionally(new JoltException("Printing to PDF failed: " + message));
        }
    }

    /** {@code finished}: the operation is over, successfully or not. */
    private static void onFinished(MemorySegment operation, MemorySegment data) {
        Pending pending = Registry.take(data.address(), Pending.class);
        try {
            if (pending != null && !pending.result().isDone()) {
                try {
                    pending.result().complete(Files.readAllBytes(pending.file()));
                } catch (IOException e) {
                    pending.result().completeExceptionally(new JoltException("The PDF could not be read back: " + e.getMessage(), e));
                }
            }
        } finally {
            if (pending != null) {
                deleteQuietly(pending.file());
            }
            call(H.objectUnref, operation);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.getLogger("io.github.b077as.jolt.linux").log(System.Logger.Level.DEBUG, "could not delete {0}: {1}", file, e.toString());
        }
    }

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
