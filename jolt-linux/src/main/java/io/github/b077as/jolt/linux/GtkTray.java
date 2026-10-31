package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Tray;
import io.github.b077as.jolt.TrayMenu;
import io.github.b077as.jolt.internal.Icons;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A tray icon on Linux: a StatusNotifierItem ({@code org.kde.StatusNotifierItem}) for the icon and a {@code com.canonical.dbusmenu}
 * for its menu, both exported over the session bus and announced to the desktop's StatusNotifierWatcher. KDE Plasma, XFCE, Cinnamon,
 * MATE, Budgie and others show it; GNOME needs its AppIndicator extension, and with none (no host) {@link #hostAvailable()} is
 * false and nothing is created.
 *
 * <p>Everything runs on the UI thread: GDBus calls the exported objects back on the main context that was current when they were
 * registered, which is the GTK main loop. The state is therefore confined to that thread.
 */
final class GtkTray implements Tray {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private static final Arena LIBS = Arena.global();
    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", LIBS);
    private static final SymbolLookup GIO = SymbolLookup.libraryLookup("libgio-2.0.so.0", LIBS);
    private static final SymbolLookup PIXBUF = SymbolLookup.libraryLookup("libgdk_pixbuf-2.0.so.0", LIBS);

    private static final MethodHandle G_BUS_GET_SYNC = Native.downcall(GIO, "g_bus_get_sync", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_SYNC = Native.downcall(GIO, "g_dbus_connection_call_sync",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle NODE_INFO_NEW_FOR_XML = Native.downcall(GIO, "g_dbus_node_info_new_for_xml",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle NODE_INFO_LOOKUP_INTERFACE = Native.downcall(GIO, "g_dbus_node_info_lookup_interface",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle NODE_INFO_UNREF = Native.downcall(GIO, "g_dbus_node_info_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle REGISTER_OBJECT = Native.downcall(GIO, "g_dbus_connection_register_object",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle UNREGISTER_OBJECT = Native.downcall(GIO, "g_dbus_connection_unregister_object",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle EMIT_SIGNAL = Native.downcall(GIO, "g_dbus_connection_emit_signal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RETURN_VALUE = Native.downcall(GIO, "g_dbus_method_invocation_return_value",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle RETURN_DBUS_ERROR = Native.downcall(GIO, "g_dbus_method_invocation_return_dbus_error",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle VARIANT_PARSE = Native.downcall(GLIB, "g_variant_parse",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_TYPE_NEW = Native.downcall(GLIB, "g_variant_type_new", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_TYPE_FREE = Native.downcall(GLIB, "g_variant_type_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle VARIANT_UNREF = Native.downcall(GLIB, "g_variant_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle VARIANT_PRINT = Native.downcall(GLIB, "g_variant_print", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle VARIANT_N_CHILDREN = Native.downcall(GLIB, "g_variant_n_children", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle VARIANT_GET_CHILD_VALUE = Native.downcall(GLIB, "g_variant_get_child_value",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle VARIANT_GET_INT32 = Native.downcall(GLIB, "g_variant_get_int32", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle VARIANT_GET_STRING = Native.downcall(GLIB, "g_variant_get_string",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle PIXBUF_LOADER_NEW = Native.downcall(PIXBUF, "gdk_pixbuf_loader_new", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_WRITE = Native.downcall(PIXBUF, "gdk_pixbuf_loader_write",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_CLOSE = Native.downcall(PIXBUF, "gdk_pixbuf_loader_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_GET_PIXBUF = Native.downcall(PIXBUF, "gdk_pixbuf_loader_get_pixbuf",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PIXBUF_SCALE_SIMPLE = Native.downcall(PIXBUF, "gdk_pixbuf_scale_simple",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle PIXBUF_GET_PIXELS = Native.downcall(PIXBUF, "gdk_pixbuf_get_pixels", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PIXBUF_GET_ROWSTRIDE = Native.downcall(PIXBUF, "gdk_pixbuf_get_rowstride", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle PIXBUF_GET_N_CHANNELS = Native.downcall(PIXBUF, "gdk_pixbuf_get_n_channels", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final int G_BUS_TYPE_SESSION = 2;
    private static final String[] WATCHERS = {"org.kde.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher"};
    private static final String ITEM_INTERFACE = "org.kde.StatusNotifierItem";
    private static final String MENU_INTERFACE = "com.canonical.dbusmenu";
    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final int[] PIXMAP_SIZES = {24, 48};
    private static final int CALL_TIMEOUT_MS = 3000;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final String ITEM_XML = """
            <node><interface name='org.kde.StatusNotifierItem'>
              <property name='Category' type='s' access='read'/>
              <property name='Id' type='s' access='read'/>
              <property name='Title' type='s' access='read'/>
              <property name='Status' type='s' access='read'/>
              <property name='WindowId' type='u' access='read'/>
              <property name='IconName' type='s' access='read'/>
              <property name='IconPixmap' type='a(iiay)' access='read'/>
              <property name='OverlayIconName' type='s' access='read'/>
              <property name='OverlayIconPixmap' type='a(iiay)' access='read'/>
              <property name='AttentionIconName' type='s' access='read'/>
              <property name='AttentionIconPixmap' type='a(iiay)' access='read'/>
              <property name='AttentionMovieName' type='s' access='read'/>
              <property name='ToolTip' type='(sa(iiay)ss)' access='read'/>
              <property name='ItemIsMenu' type='b' access='read'/>
              <property name='Menu' type='o' access='read'/>
              <method name='ContextMenu'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>
              <method name='Activate'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>
              <method name='SecondaryActivate'><arg type='i' direction='in'/><arg type='i' direction='in'/></method>
              <method name='Scroll'><arg type='i' direction='in'/><arg type='s' direction='in'/></method>
              <signal name='NewTitle'/><signal name='NewIcon'/><signal name='NewAttentionIcon'/><signal name='NewOverlayIcon'/>
              <signal name='NewToolTip'/><signal name='NewStatus'><arg type='s'/></signal>
            </interface></node>""";

    private static final String MENU_XML = """
            <node><interface name='com.canonical.dbusmenu'>
              <property name='Version' type='u' access='read'/>
              <property name='TextDirection' type='s' access='read'/>
              <property name='Status' type='s' access='read'/>
              <property name='IconThemePath' type='as' access='read'/>
              <method name='GetLayout'>
                <arg type='i' direction='in'/><arg type='i' direction='in'/><arg type='as' direction='in'/>
                <arg type='u' direction='out'/><arg type='(ia{sv}av)' direction='out'/>
              </method>
              <method name='GetGroupProperties'>
                <arg type='ai' direction='in'/><arg type='as' direction='in'/><arg type='a(ia{sv})' direction='out'/>
              </method>
              <method name='GetProperty'><arg type='i' direction='in'/><arg type='s' direction='in'/><arg type='v' direction='out'/></method>
              <method name='Event'>
                <arg type='i' direction='in'/><arg type='s' direction='in'/><arg type='v' direction='in'/><arg type='u' direction='in'/>
              </method>
              <method name='EventGroup'><arg type='a(isvu)' direction='in'/><arg type='ai' direction='out'/></method>
              <method name='AboutToShow'><arg type='i' direction='in'/><arg type='b' direction='out'/></method>
              <method name='AboutToShowGroup'><arg type='ai' direction='in'/><arg type='ai' direction='out'/><arg type='ai' direction='out'/></method>
              <signal name='ItemsPropertiesUpdated'><arg type='a(ia{sv})'/><arg type='a(ias)'/></signal>
              <signal name='LayoutUpdated'><arg type='u'/><arg type='i'/></signal>
              <signal name='ItemActivationRequested'><arg type='i'/><arg type='u'/></signal>
            </interface></node>""";

    /** One entry of the exported menu. */
    private record Node(int id, String label, boolean separator, boolean enabled, int toggle, List<Node> children) {}

    /** A square icon at one size as ARGB32 bytes, the format of {@code IconPixmap}. */
    private record Pixmap(int size, byte[] argb) {}

    private final LinuxApplication app;
    private final long registryId;
    private final String appId;
    private MemorySegment bus = MemorySegment.NULL;
    private String busName;
    private String watcher;
    private int itemObject;
    private int menuObject;
    private boolean closed;

    private List<Pixmap> pixmaps = List.of();
    private String tooltip;
    private volatile Runnable clickListener = () -> {};
    private Node root = new Node(0, "", false, true, -1, List.of());
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private int revision = 1;

    /** UI thread. @throws UnsupportedOperationException if the desktop has no tray host */
    GtkTray(LinuxApplication app, String appId, byte[] icon, String tooltip) {
        this.app = app;
        this.appId = appId;
        this.tooltip = tooltip;
        this.registryId = Registry.add(this);
        this.pixmaps = icon == null ? List.of() : decode(icon);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            bus = (MemorySegment) call(G_BUS_GET_SYNC, G_BUS_TYPE_SESSION, MemorySegment.NULL, error);
            if (bus.equals(MemorySegment.NULL)) {
                throw new UnsupportedOperationException("no session bus: " + takeError(error));
            }
            watcher = findWatcher(bus);
            if (watcher == null) {
                throw new UnsupportedOperationException("the desktop has no status notifier host (no tray)");
            }
            busName = "org.kde.StatusNotifierItem-" + ProcessHandle.current().pid() + "-" + COUNTER.incrementAndGet();
            MemorySegment owner = callBus("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                    "(su)", "(" + quote(busName) + ", uint32 0)");
            if (owner.equals(MemorySegment.NULL)) {
                throw new JoltException("could not take the bus name " + busName);
            }
            unref(owner);
            itemObject = export(ITEM_XML, ITEM_INTERFACE, ITEM_PATH);
            menuObject = export(MENU_XML, MENU_INTERFACE, MENU_PATH);
            MemorySegment registered = callBus(watcher, "/StatusNotifierWatcher", watcher, "RegisterStatusNotifierItem",
                    "(s)", "(" + quote(busName) + ",)");
            if (registered.equals(MemorySegment.NULL)) {
                throw new JoltException("the status notifier watcher refused the tray icon");
            }
            unref(registered);
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    /** The name this icon owns on the bus. For tests. */
    String busName() {
        return busName;
    }

    // ---- availability ----

    /** Whether a status notifier host is running on the session bus right now. Blocking (up to a few seconds); any thread. */
    static boolean hostAvailable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment bus = (MemorySegment) call(G_BUS_GET_SYNC, G_BUS_TYPE_SESSION, MemorySegment.NULL, error);
            if (bus.equals(MemorySegment.NULL)) {
                takeError(error);
                return false;
            }
            try {
                return findWatcher(bus) != null;
            } finally {
                Gtk.unref(bus);
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The name of the watcher that has a host registered, or {@code null}. */
    private static String findWatcher(MemorySegment bus) {
        for (String name : WATCHERS) {
            MemorySegment hasOwner = callOn(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
                    "(s)", "(" + quote(name) + ",)");
            if (hasOwner.equals(MemorySegment.NULL)) {
                continue;
            }
            boolean owned = print(hasOwner).contains("true");
            unref(hasOwner);
            if (!owned) {
                continue;
            }
            MemorySegment registered = callOn(bus, name, "/StatusNotifierWatcher", "org.freedesktop.DBus.Properties", "Get",
                    "(ss)", "(" + quote(name) + ", 'IsStatusNotifierHostRegistered')");
            if (registered.equals(MemorySegment.NULL)) {
                continue;
            }
            boolean host = print(registered).contains("true");
            unref(registered);
            if (host) {
                return name;
            }
        }
        return null;
    }

    // ---- Tray (any thread) ----

    @Override
    public void setIcon(byte[] icon) {
        byte[] copy = icon == null ? null : icon.clone();
        app.dispatch(() -> {
            if (!closed && copy != null) {
                List<Pixmap> decoded = decode(copy);
                if (!decoded.isEmpty()) {
                    pixmaps = decoded;
                    emit(ITEM_PATH, ITEM_INTERFACE, "NewIcon", null);
                }
            }
        });
    }

    @Override
    public void setTooltip(String text) {
        app.dispatch(() -> {
            if (!closed) {
                tooltip = text;
                emit(ITEM_PATH, ITEM_INTERFACE, "NewToolTip", null);
                emit(ITEM_PATH, ITEM_INTERFACE, "NewTitle", null);
            }
        });
    }

    @Override
    public void setMenu(TrayMenu menu) {
        app.dispatch(() -> {
            if (closed) {
                return;
            }
            actions.clear();
            int[] next = {1};
            root = new Node(0, "", false, true, -1, menu == null ? List.of() : nodes(menu.items(), next));
            revision++;
            emit(MENU_PATH, MENU_INTERFACE, "LayoutUpdated", "(uint32 " + revision + ", 0)", "(ui)");
        });
    }

    @Override
    public void onClick(Runnable listener) {
        clickListener = listener == null ? () -> {} : listener;
    }

    @Override
    public void close() {
        app.dispatch(this::closeNow);
    }

    /** UI thread. */
    void closeNow() {
        if (closed) {
            return;
        }
        closed = true;
        release();
        app.trayClosed(this);
    }

    private void release() {
        if (!bus.equals(MemorySegment.NULL)) {
            if (itemObject != 0) {
                call(UNREGISTER_OBJECT, bus, itemObject);
            }
            if (menuObject != 0) {
                call(UNREGISTER_OBJECT, bus, menuObject);
            }
            itemObject = menuObject = 0;
            if (busName != null) {
                MemorySegment released = callBus("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ReleaseName",
                        "(s)", "(" + quote(busName) + ",)");
                if (!released.equals(MemorySegment.NULL)) {
                    unref(released);
                }
            }
            Gtk.unref(bus);
            bus = MemorySegment.NULL;
        }
        Registry.remove(registryId);
    }

    // ---- the exported objects (UI thread, called by GDBus) ----

    private int export(String xml, String interfaceName, String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment node = (MemorySegment) call(NODE_INFO_NEW_FOR_XML, Gtk.utf8(arena, xml), error);
            if (node.equals(MemorySegment.NULL)) {
                throw new JoltException("bad interface description: " + takeError(error));
            }
            try {
                MemorySegment info = (MemorySegment) call(NODE_INFO_LOOKUP_INTERFACE, node, Gtk.utf8(arena, interfaceName));
                // GDBusInterfaceVTable: method_call, get_property, set_property, padding[8]. Lives for the process (the info keeps a pointer to it).
                MemorySegment vtable = Arena.global().allocate(8L * 11, 8);
                vtable.set(ADDRESS, 0, Callbacks.DBUS_METHOD_CALL);
                vtable.set(ADDRESS, 8, Callbacks.DBUS_GET_PROPERTY);
                int id = (int) call(REGISTER_OBJECT, bus, Gtk.utf8(arena, path), info, vtable, MemorySegment.ofAddress(registryId),
                        MemorySegment.NULL, error);
                if (id == 0) {
                    throw new JoltException("could not export " + interfaceName + ": " + takeError(error));
                }
                return id;
            } finally {
                call(NODE_INFO_UNREF, node);
            }
        }
    }

    /** A method call on one of the two exported interfaces. Always answers the invocation. */
    void onMethodCall(String interfaceName, String method, MemorySegment parameters, MemorySegment invocation) {
        try {
            if (closed) {
                fail(invocation, "org.freedesktop.DBus.Error.UnknownObject", "the tray icon has been removed");
            } else if (ITEM_INTERFACE.equals(interfaceName)) {
                itemMethod(method, invocation);
            } else {
                menuMethod(method, parameters, invocation);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "a tray call failed: " + method, e);
            fail(invocation, "org.freedesktop.DBus.Error.Failed", String.valueOf(e.getMessage()));
        }
    }

    private void itemMethod(String method, MemorySegment invocation) {
        if (method.equals("Activate")) {
            app.dispatch(clickListener);
        } // ContextMenu, SecondaryActivate and Scroll: the host draws the menu itself, nothing to do
        reply(invocation, null);
    }

    private void menuMethod(String method, MemorySegment parameters, MemorySegment invocation) {
        switch (method) {
            case "GetLayout" -> {
                int parent = childInt(parameters, 0);
                Node start = parent == 0 ? root : find(root, parent);
                if (start == null) {
                    fail(invocation, "org.freedesktop.DBus.Error.InvalidArgs", "no menu item " + parent);
                    return;
                }
                reply(invocation, parse("(u(ia{sv}av))", "(uint32 " + revision + ", " + layout(start) + ")"));
            }
            case "GetGroupProperties" -> {
                List<Integer> wanted = childIntArray(parameters, 0);
                List<Node> all = new ArrayList<>();
                flatten(root, all);
                StringBuilder text = new StringBuilder("(");
                List<String> entries = new ArrayList<>();
                for (Node node : all) {
                    if (wanted.isEmpty() || wanted.contains(node.id())) {
                        entries.add("(" + node.id() + ", " + properties(node) + ")");
                    }
                }
                text.append(entries.isEmpty() ? "@a(ia{sv}) []" : "[" + String.join(", ", entries) + "]").append(",)");
                reply(invocation, parse("(a(ia{sv}))", text.toString()));
            }
            case "GetProperty" -> {
                Node node = find(root, childInt(parameters, 0));
                String name = childString(parameters, 1);
                String value = node == null ? null : propertyEntries(node).get(name);
                if (value == null) {
                    fail(invocation, "org.freedesktop.DBus.Error.InvalidArgs", "no such property");
                } else {
                    reply(invocation, parse("(v)", "(" + value + ",)"));
                }
            }
            case "Event" -> {
                if (childString(parameters, 1).equals("clicked")) {
                    run(childInt(parameters, 0));
                }
                reply(invocation, null);
            }
            case "EventGroup" -> {
                MemorySegment events = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, parameters, 0L);
                long count = (long) call(VARIANT_N_CHILDREN, events);
                for (long i = 0; i < count; i++) {
                    MemorySegment event = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, events, i);
                    if (childString(event, 1).equals("clicked")) {
                        run(childInt(event, 0));
                    }
                    unref(event);
                }
                unref(events);
                reply(invocation, parse("(ai)", "(@ai [],)"));
            }
            case "AboutToShow" -> reply(invocation, parse("(b)", "(false,)"));
            case "AboutToShowGroup" -> reply(invocation, parse("(aiai)", "(@ai [], @ai [])"));
            default -> fail(invocation, "org.freedesktop.DBus.Error.UnknownMethod", "no method " + method);
        }
    }

    private void run(int id) {
        Runnable action = actions.get(id);
        if (action != null) {
            app.dispatch(action);
        }
    }

    /** The value of a property, as a GVariant the caller must unref later (see {@link #deferUnref}); {@code null} for an unknown one. */
    MemorySegment onGetProperty(String interfaceName, String property) {
        String text;
        String type;
        if (ITEM_INTERFACE.equals(interfaceName)) {
            switch (property) {
                case "Category" -> { type = "s"; text = "'ApplicationStatus'"; }
                case "Id" -> { type = "s"; text = quote(appId); }
                case "Title" -> { type = "s"; text = quote(tooltip != null ? tooltip : appId); }
                case "Status" -> { type = "s"; text = "'Active'"; }
                case "WindowId" -> { type = "u"; text = "uint32 0"; }
                case "IconName", "OverlayIconName", "AttentionIconName", "AttentionMovieName" -> { type = "s"; text = "''"; }
                case "IconPixmap" -> { type = "a(iiay)"; text = pixmapsText(); }
                case "OverlayIconPixmap", "AttentionIconPixmap" -> { type = "a(iiay)"; text = "@a(iiay) []"; }
                case "ToolTip" -> {
                    type = "(sa(iiay)ss)";
                    text = "('', @a(iiay) [], " + quote(tooltip == null ? "" : tooltip) + ", '')";
                }
                case "ItemIsMenu" -> { type = "b"; text = "false"; }
                case "Menu" -> { type = "o"; text = "objectpath " + quote(MENU_PATH); }
                default -> { return MemorySegment.NULL; }
            }
        } else {
            switch (property) {
                case "Version" -> { type = "u"; text = "uint32 3"; }
                case "TextDirection" -> { type = "s"; text = "'ltr'"; }
                case "Status" -> { type = "s"; text = "'normal'"; }
                case "IconThemePath" -> { type = "as"; text = "@as []"; }
                default -> { return MemorySegment.NULL; }
            }
        }
        MemorySegment value = parse(type, text);
        deferUnref(value);
        return value;
    }

    private String pixmapsText() {
        if (pixmaps.isEmpty()) {
            return "@a(iiay) []";
        }
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < pixmaps.size(); i++) {
            Pixmap pixmap = pixmaps.get(i);
            if (i > 0) {
                text.append(", ");
            }
            text.append('(').append(pixmap.size()).append(", ").append(pixmap.size()).append(", [");
            for (int b = 0; b < pixmap.argb().length; b++) {
                if (b > 0) {
                    text.append(',');
                }
                text.append(pixmap.argb()[b] & 0xFF);
            }
            text.append("])");
        }
        return text.append(']').toString();
    }

    // ---- the menu model ----

    private List<Node> nodes(List<TrayMenu.Item> items, int[] next) {
        List<Node> result = new ArrayList<>();
        for (TrayMenu.Item item : items) {
            int id = next[0]++;
            switch (item) {
                case TrayMenu.Separator separator -> result.add(new Node(id, "", true, true, -1, List.of()));
                case TrayMenu.Action action -> {
                    result.add(new Node(id, action.label(), false, action.enabled(), -1, List.of()));
                    actions.put(id, action.onSelect());
                }
                case TrayMenu.Check check -> {
                    result.add(new Node(id, check.label(), false, check.enabled(), check.checked() ? 1 : 0, List.of()));
                    boolean wanted = !check.checked();
                    Consumer<Boolean> toggle = check.onToggle();
                    actions.put(id, () -> toggle.accept(wanted));
                }
                case TrayMenu.Submenu submenu ->
                        result.add(new Node(id, submenu.label(), false, submenu.enabled(), -1, nodes(submenu.items(), next)));
            }
        }
        return result;
    }

    private static Node find(Node from, int id) {
        if (from.id() == id) {
            return from;
        }
        for (Node child : from.children()) {
            Node found = find(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void flatten(Node node, List<Node> into) {
        into.add(node);
        for (Node child : node.children()) {
            flatten(child, into);
        }
    }

    /** The properties of one entry as GVariant text values, by name. */
    private static Map<String, String> propertyEntries(Node node) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (node.id() == 0) {
            entries.put("children-display", "<'submenu'>");
            return entries;
        }
        if (node.separator()) {
            entries.put("type", "<'separator'>");
        } else {
            entries.put("label", "<" + quote(node.label().replace("_", "__")) + ">"); // an underscore marks a keyboard shortcut
        }
        entries.put("enabled", "<" + node.enabled() + ">");
        entries.put("visible", "<true>");
        if (node.toggle() >= 0) {
            entries.put("toggle-type", "<'checkmark'>");
            entries.put("toggle-state", "<" + node.toggle() + ">");
        }
        if (!node.children().isEmpty()) {
            entries.put("children-display", "<'submenu'>");
        }
        return entries;
    }

    private static String properties(Node node) {
        Map<String, String> entries = propertyEntries(node);
        if (entries.isEmpty()) {
            return "@a{sv} {}";
        }
        StringBuilder text = new StringBuilder("{");
        entries.forEach((name, value) -> text.append(text.length() > 1 ? ", " : "").append(quote(name)).append(": ").append(value));
        return text.append('}').toString();
    }

    private static String layout(Node node) {
        StringBuilder children = new StringBuilder();
        for (Node child : node.children()) {
            children.append(children.length() > 0 ? ", " : "").append('<').append(layout(child)).append('>');
        }
        return "(" + node.id() + ", " + properties(node) + ", " + (children.length() == 0 ? "@av []" : "[" + children + "]") + ")";
    }

    // ---- icons ----

    /** Decodes PNG (or the PNG image inside an .ico) with gdk-pixbuf and scales it to the pixmap sizes. Empty if it cannot be read. */
    private static List<Pixmap> decode(byte[] data) {
        byte[] png = data;
        if (Icons.isIco(data)) {
            byte[] inside = Icons.largestPng(data);
            if (inside != null) {
                png = inside;
            }
        }
        List<Pixmap> result = new ArrayList<>();
        MemorySegment loader = (MemorySegment) call(PIXBUF_LOADER_NEW);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment closeError = arena.allocate(ADDRESS);
            MemorySegment image = arena.allocateFrom(JAVA_BYTE, png);
            boolean written = (int) call(PIXBUF_LOADER_WRITE, loader, image, (long) png.length, error) != 0;
            boolean finished = (int) call(PIXBUF_LOADER_CLOSE, loader, closeError) != 0; // always required
            takeError(error);
            takeError(closeError);
            MemorySegment pixbuf = written && finished ? (MemorySegment) call(PIXBUF_LOADER_GET_PIXBUF, loader) : MemorySegment.NULL;
            if (pixbuf.equals(MemorySegment.NULL)) {
                LOG.log(System.Logger.Level.WARNING, "The tray icon could not be decoded; it must be PNG or ICO data");
                return List.of();
            }
            for (int size : PIXMAP_SIZES) {
                MemorySegment scaled = (MemorySegment) call(PIXBUF_SCALE_SIMPLE, pixbuf, size, size, 2 /* GDK_INTERP_BILINEAR */);
                if (scaled.equals(MemorySegment.NULL)) {
                    continue;
                }
                try {
                    int stride = (int) call(PIXBUF_GET_ROWSTRIDE, scaled);
                    int channels = (int) call(PIXBUF_GET_N_CHANNELS, scaled);
                    MemorySegment pixels = ((MemorySegment) call(PIXBUF_GET_PIXELS, scaled)).reinterpret((long) stride * size);
                    byte[] argb = new byte[size * size * 4];
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            long at = (long) y * stride + (long) x * channels;
                            int out = (y * size + x) * 4;
                            argb[out] = channels == 4 ? pixels.get(JAVA_BYTE, at + 3) : (byte) 0xFF; // A
                            argb[out + 1] = pixels.get(JAVA_BYTE, at);                                // R
                            argb[out + 2] = pixels.get(JAVA_BYTE, at + 1);                            // G
                            argb[out + 3] = pixels.get(JAVA_BYTE, at + 2);                            // B
                        }
                    }
                    result.add(new Pixmap(size, argb));
                } finally {
                    Gtk.unref(scaled);
                }
            }
            return result;
        } finally {
            Gtk.unref(loader);
        }
    }

    // ---- GDBus / GVariant plumbing ----

    private void emit(String path, String interfaceName, String signal, String text) {
        emit(path, interfaceName, signal, text, null);
    }

    private void emit(String path, String interfaceName, String signal, String text, String type) {
        if (bus.equals(MemorySegment.NULL)) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment parameters = text == null ? MemorySegment.NULL : parse(type, text);
            call(EMIT_SIGNAL, bus, MemorySegment.NULL, Gtk.utf8(arena, path), Gtk.utf8(arena, interfaceName), Gtk.utf8(arena, signal),
                    parameters, error);
            takeError(error);
            if (!parameters.equals(MemorySegment.NULL)) {
                unref(parameters);
            }
        }
    }

    private static void reply(MemorySegment invocation, MemorySegment value) {
        call(RETURN_VALUE, invocation, value == null ? MemorySegment.NULL : value);
        if (value != null) {
            unref(value); // the invocation took its own reference
        }
    }

    static void fail(MemorySegment invocation, String errorName, String message) {
        try (Arena arena = Arena.ofConfined()) {
            call(RETURN_DBUS_ERROR, invocation, Gtk.utf8(arena, errorName), Gtk.utf8(arena, message));
        }
    }

    /** GDBus takes its own reference to a property value and drops it when done; ours is released after the callback has returned. */
    private void deferUnref(MemorySegment value) {
        app.dispatch(() -> unref(value));
    }

    private MemorySegment callBus(String destination, String path, String interfaceName, String method, String type, String text) {
        return callOn(bus, destination, path, interfaceName, method, type, text);
    }

    /** A synchronous method call; the reply (unref it) or {@link MemorySegment#NULL} on failure. */
    private static MemorySegment callOn(MemorySegment connection, String destination, String path, String interfaceName, String method,
                                        String type, String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment parameters = parse(type, text);
            try {
                MemorySegment reply = (MemorySegment) call(CALL_SYNC, connection, Gtk.utf8(arena, destination), Gtk.utf8(arena, path),
                        Gtk.utf8(arena, interfaceName), Gtk.utf8(arena, method), parameters, MemorySegment.NULL, 0, CALL_TIMEOUT_MS,
                        MemorySegment.NULL, error);
                if (reply.equals(MemorySegment.NULL)) {
                    LOG.log(System.Logger.Level.DEBUG, "{0}.{1} failed: {2}", interfaceName, method, takeError(error));
                }
                return reply;
            } finally {
                unref(parameters);
            }
        }
    }

    private static MemorySegment parse(String type, String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment variantType = (MemorySegment) call(VARIANT_TYPE_NEW, Gtk.utf8(arena, type));
            try {
                MemorySegment value = (MemorySegment) call(VARIANT_PARSE, variantType, Gtk.utf8(arena, text), MemorySegment.NULL,
                        MemorySegment.NULL, error);
                if (value.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("bad GVariant text for " + type + ": " + takeError(error) + " in " + text);
                }
                return value;
            } finally {
                call(VARIANT_TYPE_FREE, variantType);
            }
        }
    }

    private static String print(MemorySegment value) {
        MemorySegment text = (MemorySegment) call(VARIANT_PRINT, value, 0);
        try {
            return Gtk.readUtf8(text);
        } finally {
            Gtk.free(text);
        }
    }

    private static int childInt(MemorySegment tuple, long index) {
        MemorySegment child = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, tuple, index);
        try {
            return (int) call(VARIANT_GET_INT32, child);
        } finally {
            unref(child);
        }
    }

    private static String childString(MemorySegment tuple, long index) {
        MemorySegment child = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, tuple, index);
        try {
            String value = Gtk.readUtf8((MemorySegment) call(VARIANT_GET_STRING, child, MemorySegment.NULL));
            return value == null ? "" : value;
        } finally {
            unref(child);
        }
    }

    private static List<Integer> childIntArray(MemorySegment tuple, long index) {
        MemorySegment array = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, tuple, index);
        try {
            long count = (long) call(VARIANT_N_CHILDREN, array);
            List<Integer> values = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                MemorySegment item = (MemorySegment) call(VARIANT_GET_CHILD_VALUE, array, i);
                values.add((int) call(VARIANT_GET_INT32, item));
                unref(item);
            }
            return values;
        } finally {
            unref(array);
        }
    }

    private static void unref(MemorySegment variant) {
        call(VARIANT_UNREF, variant);
    }

    /** The message of a GError held in {@code location} (which is cleared and freed), or a placeholder if there is none. */
    private static String takeError(MemorySegment location) {
        MemorySegment error = location.get(ADDRESS, 0);
        if (error.equals(MemorySegment.NULL)) {
            return "unknown error";
        }
        String message = Gtk.errorMessage(error);
        Gtk.errorFree(error);
        location.set(ADDRESS, 0, MemorySegment.NULL);
        return message;
    }

    private static String quote(String s) {
        return GtkNotifications.quote(s);
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
