package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JumpItem;
import io.github.b077as.jolt.JumpList;
import io.github.b077as.jolt.internal.CommandLines;
import io.github.b077as.jolt.internal.Icons;
import io.github.b077as.jolt.internal.RunningCommand;
import io.github.b077as.jolt.internal.ffi.Guid;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The jump list of the application: the entries in the menu of its taskbar icon ({@code ICustomDestinationList}). Tasks are shell
 * links that start the program with arguments; categories are named groups of such links. UI thread (the COM apartment).
 *
 * <p>The list belongs to an <i>AppUserModelID</i>, and the taskbar shows it on the icon of the windows with the same id. A Java program's
 * windows would otherwise belong to {@code javaw.exe}, so {@link #setWindowAppId} gives each window the application's id.
 */
final class WindowsJumpList {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
    private static final MethodHandle SH_GET_PROPERTY_STORE_FOR_WINDOW = Native.downcall(SHELL32, "SHGetPropertyStoreForWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private static final String CLSID_DESTINATION_LIST = "77f10cf0-3db5-4966-b520-b7c54fd35ed6";
    private static final String IID_DESTINATION_LIST = "6332debf-87b5-4670-90c0-5e57b408a49e";
    private static final String CLSID_OBJECT_COLLECTION = "2d3468c1-36a7-43b6-ac24-d3f02fd9607a";
    private static final String IID_OBJECT_COLLECTION = "5632b1a4-e38a-400a-928a-d4cd63230295";
    private static final String IID_OBJECT_ARRAY = "92ca9dcd-5622-4bba-a805-5e9f541bd8c9";
    private static final String CLSID_SHELL_LINK = "00021401-0000-0000-c000-000000000046";
    private static final String IID_SHELL_LINK = "000214f9-0000-0000-c000-000000000046";
    private static final String IID_PROPERTY_STORE = "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99";
    private static final String KEY_TITLE = "f29f85e0-4ff9-1068-ab91-08002b27b3d9"; // PKEY_Title, pid 2
    private static final String KEY_APP_ID = "9f4c2855-9f79-4b39-a8d0-e1d42de1d5f3"; // PKEY_AppUserModel_ID, pid 5

    // vtable slots (IUnknown 0-2)
    private static final int DL_SET_APP_ID = 3, DL_BEGIN_LIST = 4, DL_APPEND_CATEGORY = 5, DL_ADD_USER_TASKS = 7, DL_COMMIT_LIST = 8,
            DL_DELETE_LIST = 10, DL_ABORT_LIST = 11;
    private static final int COLLECTION_ADD_OBJECT = 5;
    private static final int LINK_SET_DESCRIPTION = 7, LINK_SET_ARGUMENTS = 11, LINK_SET_ICON_LOCATION = 17, LINK_SET_PATH = 20;
    private static final int STORE_SET_VALUE = 6, STORE_COMMIT = 7;
    private static final int VT_LPWSTR = 31;
    private static final int MAX_APP_ID = 128;
    static final int E_ACCESSDENIED = 0x80070005;

    private final String appId;
    private final Path iconDirectory;
    /** The HRESULT of the last commit (or delete), or {@code Integer.MIN_VALUE} before there was one. For tests. */
    volatile int lastResult = Integer.MIN_VALUE;
    /**
     * The HRESULT of the last category Windows was given (0 if all were taken, the first failure otherwise). Windows refuses categories
     * with E_ACCESSDENIED (0x80070005) when the user has turned off "Show recently opened items" in Settings; tasks are not affected. For tests.
     */
    volatile int lastCategoryResult = 0;
    /** What the last list asked Windows to show, as {@code "task:Title|args"} and {@code "Category/Title|args"} lines. For tests. */
    volatile List<String> lastEntries = List.of();

    WindowsJumpList(String appId, Path dataDirectory) {
        this.appId = appId;
        this.iconDirectory = dataDirectory.resolve("jumplist-icons");
    }

    /** Whether Windows accepts {@code appId} as an AppUserModelID. */
    static boolean validAppId(String appId) {
        return appId.length() <= MAX_APP_ID && appId.chars().noneMatch(c -> c <= ' ' || c == 0x7F);
    }

    // ---- the window's AppUserModelID ----

    /** Gives {@code hwnd} the AppUserModelID {@code id} (through its property store). Best effort; returns whether it worked. */
    static boolean setWindowAppId(MemorySegment hwnd, String id) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) SH_GET_PROPERTY_STORE_FOR_WINDOW.invokeExact(hwnd, iid(arena, IID_PROPERTY_STORE), out);
            if (hr < 0) {
                LOG.log(System.Logger.Level.WARNING, "The window's property store could not be opened: " + Com.hex(hr));
                return false;
            }
            MemorySegment store = Native.readPointer(out);
            try {
                return setString(arena, store, KEY_APP_ID, 5, id) && Com.call(store, STORE_COMMIT, Com.HR_VOID) >= 0;
            } finally {
                Com.release(store);
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Setting the window's AppUserModelID failed", t);
            return false;
        }
    }

    // ---- the list ----

    /** Sets the list, or deletes it if there is nothing in it. */
    void set(JumpList list) {
        List<String> entries = new ArrayList<>();
        MemorySegment destinations = MemorySegment.NULL;
        boolean begun = false;
        try (Arena arena = Arena.ofConfined()) {
            if (!validAppId(appId)) {
                LOG.log(System.Logger.Level.WARNING, "The application id is not usable as an AppUserModelID (at most " + MAX_APP_ID
                        + " characters, no spaces), so there is no jump list");
                return;
            }
            destinations = Win32.coCreateInstance(CLSID_DESTINATION_LIST, IID_DESTINATION_LIST);
            MemorySegment wideId = Native.allocWide(arena, appId);
            if (list.isEmpty()) {
                lastResult = Com.call(destinations, DL_DELETE_LIST, Com.HR_PTR, wideId);
                lastEntries = List.of();
                return;
            }
            Com.callChecked("SetAppID", destinations, DL_SET_APP_ID, Com.HR_PTR, wideId);
            MemorySegment slots = arena.allocate(JAVA_INT);
            MemorySegment removed = arena.allocate(ADDRESS);
            Com.callChecked("BeginList", destinations, DL_BEGIN_LIST, Com.HR_PTR_PTR_PTR, slots, iid(arena, IID_OBJECT_ARRAY), removed);
            begun = true;
            MemorySegment removedArray = Native.readPointer(removed);
            if (!removedArray.equals(MemorySegment.NULL)) {
                Com.release(removedArray); // what the user took off the list; Windows refuses a category that has it again
            }
            int room = slots.get(JAVA_INT, 0);

            List<String> command = list.command().isEmpty() ? RunningCommand.ofThisProcess() : list.command();
            if (command.isEmpty()) {
                throw new IllegalStateException("the program's own command line is unknown; give JumpList.builder().command(...)");
            }
            Set<Path> keep = new HashSet<>();
            int categoryResult = 0;

            for (JumpList.Category category : list.categories()) {
                List<JumpItem> items = category.items().subList(0, Math.min(category.items().size(), Math.max(room, 0)));
                if (items.isEmpty()) {
                    continue;
                }
                room -= items.size();
                List<String> categoryEntries = new ArrayList<>();
                MemorySegment collection = collection(arena, command, items, keep, category.name() + "/", categoryEntries);
                try {
                    int hr = Com.call(destinations, DL_APPEND_CATEGORY, Com.HR_PTR_PTR, Native.allocWide(arena, category.name()), collection);
                    if (hr < 0) {
                        if (categoryResult == 0) {
                            categoryResult = hr;
                        }
                        LOG.log(System.Logger.Level.WARNING, "The jump list category \"" + category.name() + "\" was refused: " + Com.hex(hr)
                                + (hr == E_ACCESSDENIED ? " (the user has turned off \"Show recently opened items\" in Windows Settings: only tasks are shown)" : ""));
                    } else {
                        entries.addAll(categoryEntries);
                    }
                } finally {
                    Com.release(collection);
                }
            }
            if (!list.tasks().isEmpty()) {
                MemorySegment collection = collection(arena, command, list.tasks(), keep, "task:", entries);
                try {
                    Com.callChecked("AddUserTasks", destinations, DL_ADD_USER_TASKS, Com.HR_PTR, collection);
                } finally {
                    Com.release(collection);
                }
            }
            lastCategoryResult = categoryResult;
            int hr = Com.call(destinations, DL_COMMIT_LIST, Com.HR_VOID);
            begun = false;
            lastResult = hr;
            if (hr < 0) {
                LOG.log(System.Logger.Level.WARNING, "Committing the jump list failed: " + Com.hex(hr));
            }
            lastEntries = List.copyOf(entries);
            removeUnusedIcons(keep);
        } catch (RuntimeException e) {
            lastResult = Com.E_FAIL;
            LOG.log(System.Logger.Level.WARNING, "The jump list could not be set: " + e);
            if (begun) {
                Com.call(destinations, DL_ABORT_LIST, Com.HR_VOID);
            }
        } finally {
            if (!destinations.equals(MemorySegment.NULL)) {
                Com.release(destinations);
            }
        }
    }

    /** An {@code IObjectCollection} holding one shell link per item. The caller releases it. */
    private MemorySegment collection(Arena arena, List<String> command, List<JumpItem> items, Set<Path> keep, String prefix,
                                     List<String> entries) {
        MemorySegment collection = Win32.coCreateInstance(CLSID_OBJECT_COLLECTION, IID_OBJECT_COLLECTION);
        try {
            for (JumpItem item : items) {
                MemorySegment link = link(arena, command, item, keep);
                try {
                    Com.callChecked("AddObject", collection, COLLECTION_ADD_OBJECT, Com.HR_PTR, link);
                } finally {
                    Com.release(link);
                }
                entries.add(prefix + item.title() + "|" + String.join(" ", item.arguments()));
            }
            return collection;
        } catch (RuntimeException e) {
            Com.release(collection);
            throw e;
        }
    }

    private MemorySegment link(Arena arena, List<String> command, JumpItem item, Set<Path> keep) {
        MemorySegment link = Win32.coCreateInstance(CLSID_SHELL_LINK, IID_SHELL_LINK);
        try {
            Com.callChecked("SetPath", link, LINK_SET_PATH, Com.HR_PTR, Native.allocWide(arena, command.get(0)));
            List<String> arguments = new ArrayList<>(command.subList(1, command.size()));
            arguments.addAll(item.arguments());
            Com.callChecked("SetArguments", link, LINK_SET_ARGUMENTS, Com.HR_PTR, Native.allocWide(arena, CommandLines.windows(arguments)));
            if (!item.description().isEmpty()) {
                Com.call(link, LINK_SET_DESCRIPTION, Com.HR_PTR, Native.allocWide(arena, item.description()));
            }
            Path icon = iconFile(item.icon());
            if (icon != null) {
                keep.add(icon);
                Com.call(link, LINK_SET_ICON_LOCATION, Com.HR_PTR_INT, Native.allocWide(arena, icon.toString()), 0);
            }
            // the title is a property of the link, not an IShellLink member
            MemorySegment store = Com.queryInterface(link, IID_PROPERTY_STORE);
            if (store.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("the shell link has no property store");
            }
            try {
                if (!setString(arena, store, KEY_TITLE, 2, item.title()) || Com.call(store, STORE_COMMIT, Com.HR_VOID) < 0) {
                    throw new IllegalStateException("the title of a jump list entry could not be set");
                }
            } finally {
                Com.release(store);
            }
            return link;
        } catch (RuntimeException e) {
            Com.release(link);
            throw e;
        }
    }

    /** {@code IPropertyStore::SetValue} with a string (VT_LPWSTR) for the property {@code guid}/{@code pid}. */
    private static boolean setString(Arena arena, MemorySegment store, String guid, int pid, String value) {
        MemorySegment key = arena.allocate(20, 4);
        key.copyFrom(MemorySegment.ofArray(Guid.bytes(guid)));
        key.set(JAVA_INT, 16, pid);
        MemorySegment variant = arena.allocate(24, 8); // PROPVARIANT: vt (2 bytes), 6 reserved, then the value
        variant.set(JAVA_SHORT, 0, (short) VT_LPWSTR);
        variant.set(ADDRESS, 8, Native.allocWide(arena, value));
        return Com.call(store, STORE_SET_VALUE, Com.HR_PTR_PTR, key, variant) >= 0;
    }

    // ---- icons ----

    /**
     * The icon as an {@code .ico} file in the data directory (Windows reads the icon of a link from a file), or {@code null} for the
     * program's own icon. A PNG is wrapped in an icon file; the name carries a hash of the picture.
     */
    private Path iconFile(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            byte[] ico;
            if (Icons.isIco(data)) {
                ico = data;
            } else if (Icons.isPng(data, 0) && data.length >= 24) {
                int width = ByteBuffer.wrap(data, 16, 4).getInt();
                int height = ByteBuffer.wrap(data, 20, 4).getInt();
                ByteBuffer out = ByteBuffer.allocate(22 + data.length).order(ByteOrder.LITTLE_ENDIAN);
                out.putShort((short) 0).putShort((short) 1).putShort((short) 1); // reserved, type icon, one image
                out.put((byte) (width >= 256 ? 0 : width)).put((byte) (height >= 256 ? 0 : height)).put((byte) 0).put((byte) 0);
                out.putShort((short) 1).putShort((short) 32).putInt(data.length).putInt(22);
                out.put(data);
                ico = out.array();
            } else {
                LOG.log(System.Logger.Level.WARNING, "A jump list icon must be PNG or ICO data; the program's icon is used");
                return null;
            }
            String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ico)).substring(0, 16) + ".ico";
            Files.createDirectories(iconDirectory);
            Path file = iconDirectory.resolve(name);
            if (!Files.isRegularFile(file)) {
                Files.write(file, ico);
            }
            return file;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            LOG.log(System.Logger.Level.WARNING, "A jump list icon could not be written: " + e);
            return null;
        }
    }

    private void removeUnusedIcons(Set<Path> keep) {
        if (!Files.isDirectory(iconDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(iconDirectory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().endsWith(".ico") && !keep.contains(file)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Unused jump list icons were not removed: " + e);
        }
    }

    private static MemorySegment iid(Arena arena, String guid) {
        return arena.allocateFrom(JAVA_BYTE, Guid.bytes(guid));
    }
}
