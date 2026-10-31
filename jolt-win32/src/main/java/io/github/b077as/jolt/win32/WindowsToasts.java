package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Notification;
import io.github.b077as.jolt.internal.ffi.Guid;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Shows WinRT toasts ({@code Windows.UI.Notifications}) from a plain, unpackaged process.
 *
 * <p>WinRT objects are COM objects with three extra {@code IInspectable} slots, so interface methods start at 6. The
 * calls are made on the UI thread, which is already a COM apartment. A toast needs an <i>AppUserModelID</i> to be
 * attributed to; an unpackaged app has none, so {@link #register} writes one key under
 * {@code HKCU\Software\Classes\AppUserModelId} (the documented way to give such an app a display name).
 */
final class WindowsToasts {

    private static final SymbolLookup COMBASE = SymbolLookup.libraryLookup("combase.dll", Arena.global());
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());

    /** {@code HRESULT RoInitialize(RO_INIT_TYPE)}: 0 single-threaded. S_FALSE (1) when the thread is already initialised. */
    private static final MethodHandle RO_INITIALIZE = Native.downcall(COMBASE, "RoInitialize",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    /** {@code HRESULT RoGetActivationFactory(HSTRING class, REFIID, void** factory)}. */
    private static final MethodHandle RO_GET_ACTIVATION_FACTORY = Native.downcall(COMBASE, "RoGetActivationFactory",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    /** {@code HRESULT RoActivateInstance(HSTRING class, IInspectable** instance)}. */
    private static final MethodHandle RO_ACTIVATE_INSTANCE = Native.downcall(COMBASE, "RoActivateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    /** {@code HRESULT WindowsCreateString(PCNZWCH, UINT32 length, HSTRING*)}. */
    private static final MethodHandle WINDOWS_CREATE_STRING = Native.downcall(COMBASE, "WindowsCreateString",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    /** {@code PCWSTR WindowsGetStringRawBuffer(HSTRING, UINT32* length)}: valid while the HSTRING lives. */
    private static final MethodHandle WINDOWS_GET_STRING_RAW_BUFFER = Native.downcall(COMBASE, "WindowsGetStringRawBuffer",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WINDOWS_DELETE_STRING = Native.downcall(COMBASE, "WindowsDeleteString",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle REG_CREATE_KEY_EX = Native.downcall(ADVAPI32, "RegCreateKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_SET_VALUE_EX = Native.downcall(ADVAPI32, "RegSetValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle REG_CLOSE_KEY = Native.downcall(ADVAPI32, "RegCloseKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final long HKEY_CURRENT_USER = 0x80000001L;
    private static final int KEY_WRITE = 0x20006;
    private static final int REG_SZ = 1;

    private static final String IID_TOAST_MANAGER_STATICS = "50AC103F-D235-4598-BBEF-98FE4D1A3AD4";
    private static final String IID_TOAST_FACTORY = "04124B20-82C6-4229-B109-FD9ED4662B53";
    private static final String IID_XML_DOCUMENT_IO = "6CD0E74E-EE65-4489-9EBF-CA43E87BA637";
    private static final String IID_XML_DOCUMENT = "F7F3A506-1E87-42D6-BCFB-B8C809FA5494";
    private static final String IID_ACTIVATED_ARGS = "E3BF92F3-C197-436F-8265-0625824F8DAC";
    private static final String IID_ACTIVATED_ARGS2 = "AB7DA512-CC61-568E-81BE-304AC31038FA";
    /** IMap of string to object, derived from its type signature. */
    private static final String IID_MAP_STRING_OBJECT = "1B0D3570-0877-5EC2-8A2C-3B9539506ACA";
    private static final String IID_PROPERTY_VALUE = "4BD682DD-7554-40E9-9A9B-82654EDE7E62";
    private static final int PROPERTY_VALUE_GET_STRING = 6 + 13; // IPropertyValue: after IInspectable, 13 getters precede GetString
    private static final String ARG_BODY = "jolt:body", ARG_REPLY = "jolt:reply", ARG_BUTTON = "jolt:button:";
    private static final String REPLY_INPUT_ID = "jolt-reply";

    /** IInspectable adds GetIids, GetRuntimeClassName, GetTrustLevel; the first method of a WinRT interface is slot 6. */
    private static final int FIRST_METHOD = 6;

    /** {@code TypedEventHandler<ToastNotification, IInspectable>}: a parameterised interface, its IID is derived from the type signature. */
    private static final String IID_ACTIVATED_HANDLER = "AB54DE2D-97D9-5528-B6AD-105AFE156530";
    /** At most this many toasts keep a click handler registered; the oldest is let go first. */
    private static final int MAX_LIVE = 32;
    private static final int ADD_ACTIVATED = FIRST_METHOD + 5;
    private static final int REMOVE_ACTIVATED = FIRST_METHOD + 6;
    private static final FunctionDescriptor HR_TOKEN = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG);

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private final String appId;
    private final WindowsApplication app;
    /** Toasts whose click handler is still registered, oldest first. UI thread only. */
    private final java.util.ArrayDeque<Live> live = new java.util.ArrayDeque<>();

    private record Live(MemorySegment toast, long token, ComHandler handler) {}

    WindowsToasts(String appId, WindowsApplication app) {
        this.appId = appId;
        this.app = app;
    }

    /** Unregisters every click handler. UI thread only; call before the application's callbacks are freed. */
    void close() {
        while (!live.isEmpty()) {
            drop(live.removeFirst());
        }
    }

    private void drop(Live l) {
        Com.call(l.toast(), REMOVE_ACTIVATED, HR_TOKEN, l.token());
        release(l.toast());
        l.handler().release();
    }

    /** Must run on the UI thread. Returns whether Windows accepted the toast. */
    boolean show(Notification notification) {
        register();
        int init = ro(() -> (int) RO_INITIALIZE.invokeExact(0));
        if (init < 0 && init != RPC_E_CHANGED_MODE) {
            throw new JoltException("RoInitialize failed: " + Com.hex(init));
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment document = loadXml(arena, toastXml(notification));
            MemorySegment toast = MemorySegment.NULL;
            MemorySegment notifier = MemorySegment.NULL;
            try {
                toast = createToast(arena, document);
                notifier = createNotifier(arena);
                MemorySegment setting = arena.allocate(JAVA_INT);
                // Windows only has a settings entry for an app after its first toast, and can answer ERROR_NOT_FOUND (0x80070490) before
                // that. The setting is only there to tell the caller why nothing appeared, so when it cannot be read the toast is tried
                // anyway: Show reports a real failure itself, and showing it is what creates the entry.
                int settingHr = Com.call(notifier, FIRST_METHOD + 2, Com.HR_PTR, setting); // get_Setting
                int enabled = settingValue(settingHr, setting.get(JAVA_INT, 0));
                if (settingHr < 0) {
                    LOG.log(System.Logger.Level.DEBUG, "The notification setting could not be read ({0}); showing the toast anyway", Com.hex(settingHr));
                }
                if (enabled != 0) { // NotificationSetting: 1 off for the app, 2 off for the user, 3 policy, 4 manifest
                    LOG.log(System.Logger.Level.INFO, "Windows has notifications turned off for this app (NotificationSetting {0})", enabled);
                    return false;
                }
                int hr = Com.call(notifier, FIRST_METHOD, Com.HR_PTR, toast); // IToastNotifier::Show
                Com.check("Showing the toast", hr);
                if (notification.onClick() != null || !notification.buttons().isEmpty() || notification.reply() != null) {
                    watchClicks(toast, notification);
                    toast = MemorySegment.NULL; // now owned by the live list
                }
                return true;
            } finally {
                release(notifier);
                release(toast);
                release(document);
            }
        }
    }

    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    /** The NotificationSetting to act on: the one Windows reported, or 0 (enabled) when it could not be read, so the toast is still tried. */
    static int settingValue(int hresult, int reported) {
        return hresult < 0 ? 0 : reported;
    }

    /**
     * The toast was clicked, a button pressed, or the reply sent. Runs on a thread Windows chose, so it only reads what the
     * event carries and hands the code of the application to the UI thread.
     */
    private void onActivated(Notification n, MemorySegment args) {
        try {
            String argument = activatedArguments(args);
            Runnable action = null;
            if (argument == null || argument.equals(ARG_BODY)) {
                action = n.onClick();
            } else if (argument.equals(ARG_REPLY) && n.reply() != null) {
                String text = userInput(args, REPLY_INPUT_ID);
                var onReply = n.reply().onReply();
                action = () -> onReply.accept(text == null ? "" : text);
            } else if (argument.startsWith(ARG_BUTTON)) {
                int index = Integer.parseInt(argument.substring(ARG_BUTTON.length()));
                if (index >= 0 && index < n.buttons().size()) {
                    action = n.buttons().get(index).action();
                }
            }
            if (action != null) {
                app.dispatch(action);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "a toast click could not be handled", e);
        }
    }

    /** The arguments string of the activation event, or null if it carries none. */
    private static String activatedArguments(MemorySegment args) {
        MemorySegment typed = Com.queryInterface(args, IID_ACTIVATED_ARGS);
        if (typed.equals(MemorySegment.NULL)) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check("Reading the toast arguments", Com.call(typed, FIRST_METHOD, Com.HR_PTR, out));
            return takeString(arena, Native.readPointer(out));
        } finally {
            release(typed);
        }
    }

    /** What the user typed into the input called id, or null if there is none. */
    private static String userInput(MemorySegment args, String id) {
        MemorySegment typed = Com.queryInterface(args, IID_ACTIVATED_ARGS2);
        if (typed.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment values = MemorySegment.NULL;
        MemorySegment map = MemorySegment.NULL;
        MemorySegment value = MemorySegment.NULL;
        MemorySegment property = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check("Reading the toast input", Com.call(typed, FIRST_METHOD, Com.HR_PTR, out)); // get_UserInput
            values = Native.readPointer(out);
            if (values.equals(MemorySegment.NULL)) {
                return null;
            }
            map = Com.queryInterface(values, IID_MAP_STRING_OBJECT);
            if (map.equals(MemorySegment.NULL)) {
                return null;
            }
            MemorySegment key = hstring(arena, id);
            try {
                if (Com.call(map, FIRST_METHOD, HR_HSTRING_PTR, key, out) < 0) { // IMap::Lookup
                    return null;
                }
            } finally {
                deleteString(key);
            }
            value = Native.readPointer(out);
            property = Com.queryInterface(value, IID_PROPERTY_VALUE);
            if (property.equals(MemorySegment.NULL)) {
                return null;
            }
            if (Com.call(property, PROPERTY_VALUE_GET_STRING, Com.HR_PTR, out) < 0) {
                return null;
            }
            return takeString(arena, Native.readPointer(out));
        } finally {
            release(property);
            release(value);
            release(map);
            release(values);
            release(typed);
        }
    }

    /** Copies an HSTRING out and frees it. */
    private static String takeString(Arena arena, MemorySegment hstring) {
        if (hstring.equals(MemorySegment.NULL)) {
            return "";
        }
        try {
            MemorySegment length = arena.allocate(JAVA_INT);
            MemorySegment chars;
            try {
                chars = (MemorySegment) WINDOWS_GET_STRING_RAW_BUFFER.invokeExact(hstring, length);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            return chars.reinterpret((long) length.get(JAVA_INT, 0) * 2).getString(0, StandardCharsets.UTF_16LE);
        } finally {
            deleteString(hstring);
        }
    }

    /**
     * Registers for the toast's {@code Activated} event and keeps the toast alive: the system calls our handler only while
     * the toast object exists. The handler runs on a thread chosen by Windows; the click action goes to the UI thread.
     */
    private void watchClicks(MemorySegment toast, Notification n) {
        ComHandler handler = ComHandler.agilePtrPtr(IID_ACTIVATED_HANDLER, (sender, args) -> onActivated(n, args), app::deferFree);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment token = arena.allocate(JAVA_LONG);
            int hr = Com.call(toast, ADD_ACTIVATED, Com.HR_PTR_PTR, handler.object, token);
            if (hr < 0) {
                handler.release();
                release(toast);
                throw new JoltException("Listening for toast clicks failed: " + Com.hex(hr));
            }
            live.addLast(new Live(toast, token.get(JAVA_LONG, 0), handler));
            while (live.size() > MAX_LIVE) {
                drop(live.removeFirst());
            }
        }
    }

    // ---- the toast ----

    static String toastXml(Notification n) {
        StringBuilder xml = new StringBuilder("<toast launch=\"" + ARG_BODY + "\"><visual><binding template=\"ToastGeneric\"><text>")
                .append(escape(n.title())).append("</text>");
        if (!n.body().isEmpty()) {
            xml.append("<text>").append(escape(n.body())).append("</text>");
        }
        xml.append("</binding></visual>");
        if (n.reply() != null || !n.buttons().isEmpty()) {
            xml.append("<actions>");
            if (n.reply() != null) {
                xml.append("<input id=\"").append(REPLY_INPUT_ID).append("\" type=\"text\" placeHolderContent=\"")
                        .append(escape(n.reply().placeholder())).append("\"/>")
                        .append("<action content=\"").append(escape(n.reply().label())).append("\" arguments=\"").append(ARG_REPLY)
                        .append("\" activationType=\"foreground\" hint-inputId=\"").append(REPLY_INPUT_ID).append("\"/>");
            }
            for (int i = 0; i < n.buttons().size(); i++) {
                xml.append("<action content=\"").append(escape(n.buttons().get(i).label())).append("\" arguments=\"")
                        .append(ARG_BUTTON).append(i).append("\" activationType=\"foreground\"/>");
            }
            xml.append("</actions>");
        }
        return xml.append("</toast>").toString();
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // XML 1.0 cannot carry most control characters, even escaped
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** {@code Windows.Data.Xml.Dom.XmlDocument} holding {@code xml}, as an {@code IXmlDocument*} (one reference). */
    private MemorySegment loadXml(Arena arena, String xml) {
        MemorySegment instance = MemorySegment.NULL;
        MemorySegment io = MemorySegment.NULL;
        MemorySegment className = hstring(arena, "Windows.Data.Xml.Dom.XmlDocument");
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check("Creating the XML document", ro(() -> (int) RO_ACTIVATE_INSTANCE.invokeExact(className, out)));
            instance = Native.readPointer(out);
            io = require(Com.queryInterface(instance, IID_XML_DOCUMENT_IO), "IXmlDocumentIO");
            MemorySegment text = hstring(arena, xml);
            Com.check("Loading the toast XML", Com.call(io, FIRST_METHOD, Com.HR_PTR, text)); // LoadXml
            MemorySegment document = require(Com.queryInterface(instance, IID_XML_DOCUMENT), "IXmlDocument");
            deleteString(text);
            return document;
        } finally {
            release(io);
            release(instance);
            deleteString(className);
        }
    }

    private MemorySegment createToast(Arena arena, MemorySegment document) {
        MemorySegment factory = activationFactory(arena, "Windows.UI.Notifications.ToastNotification", IID_TOAST_FACTORY);
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check("Creating the toast", Com.call(factory, FIRST_METHOD, Com.HR_PTR_PTR, document, out)); // CreateToastNotification
            return Native.readPointer(out);
        } finally {
            release(factory);
        }
    }

    private MemorySegment createNotifier(Arena arena) {
        MemorySegment statics = activationFactory(arena, "Windows.UI.Notifications.ToastNotificationManager",
                IID_TOAST_MANAGER_STATICS);
        MemorySegment id = hstring(arena, appId);
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            // slot 6 is CreateToastNotifier(), slot 7 CreateToastNotifierWithId(HSTRING)
            Com.check("Creating the toast notifier", Com.call(statics, FIRST_METHOD + 1, HR_HSTRING_PTR, id, out));
            return Native.readPointer(out);
        } finally {
            deleteString(id);
            release(statics);
        }
    }

    private static final FunctionDescriptor HR_HSTRING_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    private static MemorySegment activationFactory(Arena arena, String className, String iid) {
        MemorySegment name = hstring(arena, className);
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment riid = arena.allocateFrom(JAVA_BYTE, Guid.bytes(iid));
            Com.check("Finding " + className, ro(() -> (int) RO_GET_ACTIVATION_FACTORY.invokeExact(name, riid, out)));
            return Native.readPointer(out);
        } finally {
            deleteString(name);
        }
    }

    // ---- the application identity ----

    /** Gives {@code appId} a display name so Windows can attribute the toast. Idempotent. */
    private void register() {
        String subKey = "Software\\Classes\\AppUserModelId\\" + appId.replace('\\', '_');
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = arena.allocate(ADDRESS);
            int status = ro(() -> (int) REG_CREATE_KEY_EX.invokeExact(MemorySegment.ofAddress(HKEY_CURRENT_USER),
                    Native.allocWide(arena, subKey), 0, MemorySegment.NULL, 0, KEY_WRITE, MemorySegment.NULL, key,
                    MemorySegment.NULL));
            if (status != 0) {
                throw new JoltException("Registering the notification identity failed: Win32 error " + status);
            }
            MemorySegment hkey = Native.readPointer(key);
            try {
                MemorySegment name = Native.allocWide(arena, "DisplayName");
                MemorySegment value = arena.allocateFrom(appId, StandardCharsets.UTF_16LE);
                int size = (appId.length() + 1) * 2;
                int set = ro(() -> (int) REG_SET_VALUE_EX.invokeExact(hkey, name, 0, REG_SZ, value, size));
                if (set != 0) {
                    throw new JoltException("Registering the notification identity failed: Win32 error " + set);
                }
            } finally {
                ro(() -> (int) REG_CLOSE_KEY.invokeExact(hkey));
            }
        }
    }

    // ---- helpers ----

    private static MemorySegment hstring(Arena arena, String s) {
        MemorySegment chars = arena.allocateFrom(s, StandardCharsets.UTF_16LE);
        MemorySegment out = arena.allocate(ADDRESS);
        Com.check("WindowsCreateString", ro(() -> (int) WINDOWS_CREATE_STRING.invokeExact(chars, s.length(), out)));
        return Native.readPointer(out);
    }

    private static void deleteString(MemorySegment hstring) {
        if (!hstring.equals(MemorySegment.NULL)) {
            ro(() -> (int) WINDOWS_DELETE_STRING.invokeExact(hstring));
        }
    }

    private static MemorySegment require(MemorySegment pointer, String what) {
        if (pointer.equals(MemorySegment.NULL)) {
            throw new JoltException("This Windows has no " + what);
        }
        return pointer;
    }

    private static void release(MemorySegment pointer) {
        if (!pointer.equals(MemorySegment.NULL)) {
            Com.release(pointer);
        }
    }

    @FunctionalInterface
    private interface NativeCall {
        int run() throws Throwable;
    }

    private static int ro(NativeCall call) {
        try {
            return call.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
