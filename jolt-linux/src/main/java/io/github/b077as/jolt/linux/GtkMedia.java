package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Permission;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Microphone and camera for pages: WebKitGTK keeps {@code getUserMedia} off ({@code enable-media-stream}) and refuses every
 * request nobody answers. These bindings live apart from {@link Gtk} so that an older system loses only this feature:
 * {@link #available()} says whether every symbol was found.
 */
final class GtkMedia {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private record Handles(MethodHandle getSettings, MethodHandle setMediaStream, MethodHandle allow, MethodHandle isInstance,
                           long userMediaType, long deviceInfoType, MethodHandle forAudio, MethodHandle forVideo,
                           MethodHandle forDisplay /* nullable: WebKitGTK 2.34+ */) {}

    /** {@code null} when this system lacks something. */
    private static final Handles H = load();

    private GtkMedia() {}

    private static Handles load() {
        try {
            Arena libs = Arena.global();
            SymbolLookup gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", libs);
            SymbolLookup webkit = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", libs);
            MethodHandle userMediaType = Native.downcall(webkit, "webkit_user_media_permission_request_get_type",
                    FunctionDescriptor.of(JAVA_LONG));
            MethodHandle deviceInfoType = Native.downcall(webkit, "webkit_device_info_permission_request_get_type",
                    FunctionDescriptor.of(JAVA_LONG));
            MethodHandle forDisplay = null;
            try {
                forDisplay = Native.downcall(webkit, "webkit_user_media_permission_is_for_display_device",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS));
            } catch (RuntimeException | LinkageError older) {
                // WebKitGTK before 2.34: a screen-capture request cannot be told apart, so it is never granted (see below)
            }
            return new Handles(
                    Native.downcall(webkit, "webkit_web_view_get_settings", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_settings_set_enable_media_stream", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                    Native.downcall(webkit, "webkit_permission_request_allow", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gobject, "g_type_check_instance_is_a", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)),
                    (long) userMediaType.invoke(), (long) deviceInfoType.invoke(),
                    Native.downcall(webkit, "webkit_user_media_permission_is_for_audio_device", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                    Native.downcall(webkit, "webkit_user_media_permission_is_for_video_device", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                    forDisplay);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "This WebKitGTK cannot offer the microphone or camera to pages: " + t);
            return null;
        }
    }

    static boolean available() {
        return H != null;
    }

    /** Turns {@code getUserMedia} on for the view. It still asks {@code permission-request}, which the SecurityPolicy answers. */
    static void enable(MemorySegment webView) {
        if (H == null) {
            return;
        }
        try {
            MemorySegment settings = (MemorySegment) H.getSettings.invoke(webView); // owned by the view
            H.setMediaStream.invoke(settings, 1);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * The permissions a request needs, all of which must be granted; {@code null} if it is not a camera/microphone request
     * (screen capture included). A device-list request ({@code enumerateDevices}) is reported as needing none of the two
     * in particular: use {@link #isDeviceInfo} for it.
     */
    static java.util.Set<Permission> kindsOf(MemorySegment request) {
        if (H == null) {
            return null;
        }
        try {
            if (!isA(request, H.userMediaType)) {
                return null;
            }
            if (H.forDisplay == null || (int) H.forDisplay.invoke(request) != 0) {
                return null; // screen capture (or cannot tell): not covered by MICROPHONE/CAMERA
            }
            java.util.Set<Permission> kinds = java.util.EnumSet.noneOf(Permission.class);
            if ((int) H.forAudio.invoke(request) != 0) {
                kinds.add(Permission.MICROPHONE);
            }
            if ((int) H.forVideo.invoke(request) != 0) {
                kinds.add(Permission.CAMERA);
            }
            return kinds.isEmpty() ? null : kinds;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static boolean isDeviceInfo(MemorySegment request) {
        try {
            return H != null && isA(request, H.deviceInfoType);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void allow(MemorySegment request) {
        try {
            H.allow.invoke(request);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static boolean isA(MemorySegment instance, long type) throws Throwable {
        return (int) H.isInstance.invoke(instance, type) != 0;
    }
}
