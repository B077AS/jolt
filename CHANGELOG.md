# Changelog

All published versions of Jolt, newest first. Every module (`jolt-core`, `jolt-win32`, `jolt-linux`, `jolt-credentials`) is
released with the same version number. Jolt is early software (version 0.x): APIs can still change between versions, and
this file says when they do.

The latest version is the one on [Maven Central](https://central.sonatype.com/artifact/io.github.b077as/jolt-core).

## Unreleased

### Added
- **Fixed-size windows: `WindowOptions.withResizable(false)`.** The window keeps the size it was created with: no sizing border, no
  maximize box, `maximized` and `minWidth`/`minHeight` are ignored, `WebWindow.maximize()` does nothing, and Size/Maximize from the system
  menu (Alt+Space) are refused. Minimize and full screen still work. A frameless window gets no resize handles, the title bar kit leaves out
  the maximize button, and `<html>` gets `data-jolt-not-resizable` so a page can hide a maximize button of its own. `WindowOptions` gained
  the component `resizable` (last, default `true`), so code that calls its canonical constructor directly must pass it; the `with...`
  methods are unaffected. Windows: tested on real windows (style bits, system commands). Not yet verified: Linux (`gtk_window_set_resizable`;
  the compiled code has not been run, and a window manager may still treat the window its own way), how a fixed-size window looks on Windows 10.
- **Strict file drops.** Put `data-jolt-drop-strict` on `<html>` and a file drag over anything outside a `data-jolt-drop` element gets
  `dropEffect = 'none'` (the system's not-allowed cursor), and a drop there is not delivered to `onFileDrop`. Without the attribute the
  whole page accepts drops, as before. Windows (WebView2): done in the page script. Linux: only the delivery is refused; the cursor still
  shows "copy" outside zones because the host window reports the drag natively. Not yet verified: that WebKitGTK/WebView2 render
  `dropEffect = 'none'` as the not-allowed cursor.

## 0.4.1 - 2026-11-08

### Added
- **Dropping files onto a window: `WebWindow.onFileDrop(listener)`.** The listener gets a `FileDrop`: the absolute local paths of the
  dropped files and folders, the `zone` (the `data-jolt-drop` value of the element they landed on, so one window can have several drop
  areas) and the pointer position in page pixels. While files are dragged over the page, `<html>` gets `data-jolt-drop-active` and the
  area under the pointer `data-jolt-drop-over`, for CSS. Once it is used the web view never navigates to a dropped file. A page is never
  told a file's location, so the path comes from the platform (WebView2 attaches the dropped `File` objects to the page's message;
  WebKitGTK reports the drag to the window) and the page only adds the names and the zone; a message whose names do not match the
  platform's files is ignored, and paths must be absolute and local (no `\\server\share`), so a page cannot make up a file.
- **Dragging files out of a window: `WebWindow.setFileDragSource(FileDragSource)`.** A `draggable` element with `data-jolt-drag-file="token"`
  starts an operating-system drag of the files your `FileDragSource.filesFor(token)` returns (null or empty refuses); the page never names a
  file. Targets can only copy, so the original is never moved. `finished(token, dropped)` tells you how it ended (to delete a temporary file).
  Windows uses the shell's drag loop and data object; Linux offers a `text/uri-list` through GTK.

- **Buttons in the taskbar preview (Windows): `WebWindow.setTaskbarButtons(List<TaskbarButton>)`.** Up to 7 buttons under the window's preview
  when the pointer rests on its taskbar icon (the strip Discord uses for mute and leave-call), each with a PNG/ICO icon, a tooltip, an
  enabled state and a callback on the UI thread. Windows lets the buttons be added only once, so the first call fixes their number; later
  calls update them and may use fewer (the rest are hidden). They are added when the window's taskbar button exists and again after Explorer
  restarts. `supportsTaskbarButtons()` is `false` elsewhere and the call does nothing (the `WebWindow` methods are defaults, so existing
  implementations still compile). Tested on Windows: adding and updating return S_OK and presses reach the right callback (simulated with the
  `WM_COMMAND` Explorer sends).

- **Progress on the taskbar icon (Windows): `WebWindow.setTaskbarProgress(TaskbarProgress)`.** A green bar along the window's taskbar icon
  (`TaskbarProgress.normal(0.4)`), a red one (`error`), a yellow one (`paused`), a moving marquee for work of unknown length
  (`indeterminate()`), or `none()` to clear it. Any thread; the value is kept and shown once the window has a taskbar button, and again
  after Explorer restarts. On Linux it is best effort: Jolt sends the Unity launcher `com.canonical.Unity.LauncherEntry` `Update` signal, which KDE
  Plasma's task manager, Ubuntu's dock and Dash to Dock/Panel draw and other desktops (plain GNOME Shell, XFCE) ignore; the application needs an
  installed `<appId>.desktop` file, it is one bar per application, and error/paused look like a normal bar. `supportsTaskbarProgress()` is
  `true` on Linux when there is a session bus. Tested on Windows: every state is accepted (S_OK); how the bar looks is not.

- **Extra entries on the taskbar / launcher icon menu: `Application.setJumpList(JumpList)`.** The menu that opens when the user
  right-clicks the application's icon can offer **tasks** ("New incognito window") and, on Windows, your own **categories** ("Recent
  projects"). Each entry has a title and the arguments the program is started with when it is chosen (optional tooltip and icon on
  Windows), so use `SingleInstance` to hand them to the running copy. `JumpList.builder().task(...).category(...).build()`;
  `JumpList.empty()` removes them; `supportsJumpList()`. Windows: `ICustomDestinationList` with one shell link per entry; the windows
  get `AppOptions.appId` as their AppUserModelID (from the first call on) so the list sits on this application's icon and not on
  `javaw.exe`'s; an id with spaces or over 128 characters gets no list. The command that starts the program is the process's own
  (rebuilt as `-jar app.jar` or `-cp ... Main` for a Java launch, where Windows tells no arguments) unless `builder().command(...)` is
  given. Linux: the tasks are written as freedesktop **desktop actions** into the generated `$XDG_DATA_HOME/applications/<appId>.desktop`
  (the same file that carries the Wayland icon; one that Jolt did not write is never changed, and actions of a copied system entry are
  replaced); categories are left out. Not included: the "Recent" list of files (that needs a registered file type).
  Tested on Windows: Windows accepts the list (S_OK) and the windows take the id; the Linux entry text is unit tested.

### Fixed
- **Linux, Wayland: `isMinimized()` stayed `false` after `minimize()`.** xdg-shell never tells a client that its window was minimized, so
  GDK sends no iconified state. The window now records the minimize itself and takes it back when it gets the keyboard focus again.
- **Linux, Wayland: `Clipboard.onChange` never fired, not even for the application's own writes.** GTK 3 does not report clipboard
  owner changes on Wayland (`gdk_display_supports_selection_notification` is false). The application's own writes and clears are now
  announced; what other programs copy still cannot be seen on such a display.
- **Binary channels could stop delivering a large message part way, on Windows.** The connection's reader and writer were both
  virtual threads on the same socket, and with JDK 25.0.1 on Windows the writer's "socket can take more" wakeup is sometimes lost
  in that situation (the JDK's read and write pollers are separate WEPoll instances); the page then waited forever for the rest of
  the message. About 40% of 2 MB messages were affected in tests. The writer is now a platform thread. Reproduced without Jolt
  on Windows; not tried on Linux (which uses a different poller), where the change is harmless either way.
- **Closing a channel (or the window) could hang** when the page had stopped reading: `close` waited for the lock held by a write that
  could never finish. The close frame is now sent on the side and given 250 ms; the connection is then closed regardless.

### Changed
- Windows: the UI thread is now initialised with `OleInitialize` instead of `CoInitializeEx` (it does the same and also sets up what
  drag and drop needs).
- `WebView2Abi` was regenerated with three more interfaces (`ICoreWebView2WebMessageReceivedEventArgs2`, `ICoreWebView2ObjectCollectionView`,
  `ICoreWebView2File`); nothing else in it changed.

## 0.4.0 - 2026-11-07

### Added
- **Custom URL schemes: `Application.urlSchemes()`** (`register(scheme, displayName, command)`, `unregister`, `isRegistered`, and `UrlSchemes.findUrl(args, scheme)`).
  Makes the operating system start your application for links such as `myapp://invite/ABC`; you choose the scheme. Windows writes `HKCUSoftwareClasses<scheme>`;
  Linux writes a `.desktop` entry handling `x-scheme-handler/<scheme>` and runs `xdg-mime default`. The link arrives as the last command-line argument, and with
  `SingleInstance` a running copy receives it from the new launch. Web, mail and file schemes (`http`, `mailto`, ...) are refused. Windows registry round trip and the
  Linux entry file are tested on both systems; **not tried: a real browser click.**
- **Microphone and camera for your own pages: `SecurityPolicy.withGrantedPermissions(Permission.MICROPHONE, Permission.CAMERA)`.** Pages
  of the window's own content (and trusted origins) can then use `getUserMedia`, `enumerateDevices` and the rest of WebRTC while every other
  permission and every other page stays refused. Before, `getUserMedia` always failed with `NotAllowedError` unless you turned
  `denyPermissions` off, and on Linux it failed even then. Windows answers the request itself and tells WebView2 not to remember the answer, so
  changing the policy takes effect; Linux turns on WebKitGTK's `enable-media-stream` and allows the request (a system without those symbols
  just keeps refusing). Screen capture (`getDisplayMedia`) is not covered. Tested on Windows and Linux (`microphoneAccess*`, `grantedMicrophone*`).
- **Full screen: `WebWindow.setFullscreen(boolean)` and `isFullscreen()`.** The window covers the whole screen it is on, without title bar,
  border or taskbar; leaving it brings back the size, position and maximized state it had. The browser's F11 stays disabled, this is the
  way to do it. A window that is still hidden is full screen when first shown. A frameless page can do the same with
  `jolt.frame.setFullscreen(true)`, `toggleFullscreen()` and `isFullscreen()`, and sees `data-jolt-fullscreen` on `<html>` (the resize
  handles hide); `onChange` reports `fullscreen` too. Windows places a frameless-style window over the monitor (taskbar included); Linux
  uses `gtk_window_fullscreen`, which needs a window manager or compositor that honours it. `WebWindow` gained two abstract methods.
- **Ready-made title bar for frameless windows.** Put `data-jolt-titlebar` on an element and it becomes a title bar: it moves the window,
  and the minimize, maximize/restore and close buttons are added at its right end (`data-jolt-titlebar="close"` for just a close button,
  for dialogs). Their colours are CSS custom properties, so a theme is a few lines of CSS: `--jolt-caption-fg`, `--jolt-caption-bg`,
  `--jolt-caption-hover-bg`, `--jolt-caption-hover-fg`, `--jolt-caption-active-bg`, `--jolt-caption-active-fg`, `--jolt-caption-width`,
  `--jolt-caption-icon`, `--jolt-caption-inactive-opacity`, and for one button `--jolt-minimize-*`, `--jolt-maximize-*`,
  `--jolt-close-*` (the close button is red on hover by default). The buttons live in a shadow root, so page CSS cannot break them.
  Your own markup with `jolt.frame.minimize()` and friends keeps working as before.
- **The page now learns its window's state.** `<html>` gets `data-jolt-maximized` while the window is maximized, `data-jolt-inactive`
  while it is not the window in front, and `data-jolt-platform="windows"` or `"linux"`; `jolt.frame.isMaximized()`,
  `jolt.frame.isFocused()` and `jolt.frame.onChange(callback)` (returns a function that stops listening) report the same. The kit swaps its
  maximize icon for restore and dims its buttons while the window is inactive.
- **Windows 11 snap layouts over your own maximize button.** The maximize button (the kit's, or any element with `data-jolt-maximize`)
  is reported to the window, which puts a nearly invisible child window over it that answers `HTMAXBUTTON`; that is what makes
  Windows 11 show its snap layouts when the pointer rests on the button. Because the page then no longer gets the pointer, the window
  feeds hover and press back as `data-jolt-hover` / `data-jolt-pressed` on your element (the kit styles itself). Windows 10 and Linux
  ignore this; the button simply works as a button.
- **Windows: right-clicking a title bar** (`data-jolt-drag` or `data-jolt-titlebar`) opens the system window menu (Restore, Move, Size,
  Minimize, Maximize, Close); `jolt.frame.menu()` opens it from code. Frameless windows are also rounded explicitly on Windows 11.

## 0.3.3 - 2026-11-06

### Added
- **Clipboard: `Application.clipboard()`** returns a `Clipboard` for Java code, with no AWT (on Linux AWT would load a second toolkit next
  to GTK). It reads and writes text, an HTML fragment, a PNG image and a list of files; `formats()` says what is there, `clear()` empties
  it, and `onChange` reports changes (the formats only, never the content). `ClipboardContent` combines several formats in one copy, for
  example text plus HTML. Every call is asynchronous (a `CompletableFuture` completed on the UI thread), so never block the UI thread on
  one. Windows writes `CF_UNICODETEXT`, "HTML Format", "PNG" plus `CF_DIBV5` (programs that only know bitmaps still work) and
  `CF_HDROP`; Linux offers the text targets, `text/html`, `image/png`, and `text/uri-list` with `x-special/gnome-copied-files`.
- **`ClipboardContent.secret(text)`** for passwords and tokens: the value is marked so Windows clipboard history, the cloud clipboard and
  clipboard monitors skip it (Linux: `x-kde-passwordManagerHint`, honoured by Klipper and other managers), and it is taken off the
  clipboard after 30 seconds (`secret(text, Duration)` to choose) unless something else was copied meanwhile. A secret still on the
  clipboard is also removed when the application ends. Other programs can still read it while it is there.

### Security
- **The clipboard is treated as hostile input.** Content over 32 MB or more than 4096 files is refused; every length and offset in
  data from another program is checked against the data actually present; images are validated and handed out as the PNG bytes, not
  decoded by Jolt on read; text stops at the first NUL; file paths must be absolute and local (a network path such as
  `\\server\share\x`, which would make Windows send the user's credentials to that server, is dropped). Contents are never logged and
  `ClipboardContent.toString()` does not show them. Web pages get no access through Jolt: a page's own `navigator.clipboard` stays under
  `SecurityPolicy` (the default denies permission requests).

### Changed
- **Windows: browser shortcuts are disabled in the web view** (`AreBrowserAcceleratorKeysEnabled` off): Ctrl+F no longer opens the
  browser's find bar, and Ctrl+P, F5, Ctrl+R, zoom keys, F12 and Ctrl+Shift+I do nothing either. With `devTools` on, "Inspect" in the
  context menu still opens the developer tools. Not changed on Linux, where WebKitGTK has no such shortcuts. Runtimes that lack the
  setting keep the old behaviour.
- **API addition that can break code that implements `Application`** (0.x): it has the new method `clipboard()`, which by default
  throws `UnsupportedOperationException`, so only code that implements `Application` and calls it is affected.

### Not yet verified
- The Linux clipboard compiles but its tests have not been run. Still open: change events for our own writes, reads while another
  program owns the clipboard and does not answer, Wayland focus rules, and the fallback for pictures that are not PNG.

## 0.3.2 - 2026-11-02

### Fixed
- **Linux (Wayland): the window icon was the compositor's placeholder (KDE: the Wayland "W") and `setIcon` had no effect.** A Wayland
  window cannot set its own icon: the compositor shows the `Icon=` of `<appId>.desktop`. Now the icon given with `withIcon` or
  `setIcon` is also written to the user's icon theme (`$XDG_DATA_HOME/icons/hicolor/<size>/apps/<appId>-<hash>.png`, one file per size
  of an `.ico`) and `$XDG_DATA_HOME/applications/<appId>.desktop` points at it: a copy of the system's entry of that id with only the
  icon replaced, or, with none (AppImage, archive, development run), a hidden entry that starts the running program. So the title bar,
  task bar and alt-tab show the icon, it follows `setIcon` (an accent colour, say) in the task bar of a running window, and an
  AppImage gets an icon without a desktop-integration tool. An entry in that folder that Jolt did not write is left alone. Not done
  on X11, where the window icon property works.

## 0.3.1 - 2026-10-31

### Fixed
- **Windows: no notification was shown when Windows could not read the app's notification setting.** Windows keeps a settings entry for
  an app, and reading it (`get_Setting`) can fail with `ERROR_NOT_FOUND` (0x80070490). Jolt treated that as an error, so
  `Application.notify` failed with "Reading the notification setting failed" and the toast was never tried. Now a setting that cannot be
  read is treated as unknown and the toast is attempted anyway (`Show` still reports a real failure). A setting that Windows does report
  (off for the app, off for the user, policy) is still respected and still completes the future with `false`.

### Added
- **`WebWindow.isFocused()`**: whether the window is the one the user is typing into (shown, not minimized, and the active window of the
  system). Windows: the foreground window (`GetForegroundWindow`). Linux: GDK's `FOCUSED` window state, kept up to date from the
  window-state signal. Use it to decide, for example, whether new mail needs a notification or can just appear in the list. It is `false`
  while a dialog of the application is in front.

### Changed
- **API change that can break code that implements `WebWindow`** (0.x): `WebWindow` has the new abstract method `isFocused`.

## 0.3.0 - 2026-10-31

### Added
- **System notifications**: `Application.notify(Notification)` shows a toast (WinRT `Windows.UI.Notifications` on Windows, the
  `org.freedesktop.Notifications` D-Bus service on Linux). On Windows the first call registers `appId` as a display name under
  `HKCU\Software\Classes\AppUserModelId`; nothing is installed. It completes with `false` when Windows has notifications turned
  off for the user or the app. `Notification.withOnClick`, `withButton` (up to 5) and `withReply` (a text field with a send button,
  Windows only: the Linux specification has no text input) run on the UI thread while the application is running.
  `WebWindow.enableNotifications(app)` lets trusted pages call `jolt.notify(title, body)` (a promise of whether the system showed it;
  at most 5 per 10 seconds per window). The page can pass `{ onclick, buttons: [{ label, onclick }], reply: { label,
  placeholder, onreply } }` as a third argument; the callbacks run in the page when the user uses the notification.

- **More than one window (pop-outs)**: `WebWindow.enableOpenWindow(app, WindowOpener)` lets trusted pages call
  `jolt.openWindow(path, { key, title, width, height })`. The page never opens anything itself: the app's `WindowOpener` receives an
  `OpenWindowRequest`, creates the window (with `request.createWindow(app)`, which enables the new window before it can load a page) or
  refuses, and the promise resolves with whether a window opened. `path` must be an address inside the app (one leading `/`), a window
  may make at most 10 requests per 10 seconds, and asking again for the same `key` brings the existing window to the front instead of
  opening another. `ResourceOptions.withStartPath("/message/42")` makes a window open on its own page. `jolt.broadcast(text)` and
  `jolt.onBroadcast(callback)` send short text between the windows of an application (never back to the sender).
- **Owner windows**: `WindowOptions.withOwner(window)`: the new window stays in front of its owner, minimizes with it and closes with it
  (Win32 owner window; `gtk_window_set_transient_for` on Linux). `Application.windows()` lists the open windows.
- **Window state**: `WebWindow.focus()` (shows, restores and raises the window), `minimize()`, `isMinimized()`, `isVisible()`.
- **Close to tray**: `WebWindow.setCloseBehavior(CloseBehavior.HIDE)` makes the close button (and Alt+F4) only hide the window, and can be
  changed at any time. `close()`, `Application.quit()` and the end of the user's session still close it.
- **Tray icon**: `Application.isTraySupported()` and `createTray(icon, tooltip)` return a `Tray` with `setIcon`, `setTooltip`, `onClick` and
  `setMenu(TrayMenu)`; a `TrayMenu` holds actions, checkboxes, separators and submenus and is rebuilt to change it. Windows: a notification-area
  icon with a native menu (dark when the system is dark, through an undocumented call that is skipped if missing); the icon comes back if
  Explorer restarts. Linux: a StatusNotifierItem with a `dbusmenu` menu, shown by KDE Plasma, XFCE, Cinnamon, MATE, Budgie and others; stock
  GNOME needs its AppIndicator extension, and without a tray host `isTraySupported()` is `false` and `createTray` throws
  `UnsupportedOperationException`. A Linux icon is not registered again if the tray host restarts while the application runs.
- **Start with the computer**: `Application.autostart()` returns an `Autostart` (`enable(command)`, `disable()`, `isEnabled()`,
  `Autostart.currentCommand(extraArgs)`): a value under `HKCU\...\Run` on Windows, a `~/.config/autostart/<appId>.desktop` file on Linux; no
  administrator rights. Add an argument such as `--minimized` to the command and read it at launch to start hidden in the tray.
- **`SingleInstance.acquire(options, args, onOtherLaunch)`**: keeps one copy of the application per user. A second copy hands its arguments
  to the first (a lock file in the data directory and a Unix domain socket only the same user can reach) and learns it is not the primary.
- **Frameless windows**: `WindowOptions.withFrameless(true)` removes the system title bar and border. In the page, `data-jolt-drag` marks the
  area that moves the window (a double click maximizes or restores it), thin invisible handles resize it, and `jolt.frame.minimize()`,
  `toggleMaximize()` and `close()` are the window buttons. The commands travel over the normal message bridge, work with `loadHtml`, and never
  reach the application's `onMessage` listeners. Windows keeps the sizing frame, so the system should still draw the shadow and snap the
  window; Linux draws no shadow.

### Changed
- **API changes that can break code that implements Jolt's interfaces or builds its records with the long constructors** (0.x): `WebWindow`
  has the new abstract methods `focus`, `minimize`, `isMinimized`, `isVisible`, `setCloseBehavior` and `closeBehavior`; `Application` has the new
  abstract method `windows()`; the `WindowOptions` record gained the components `owner` and `frameless` (use `defaults()` and the `with...`
  methods), and `ResourceOptions` gained `startPath` (`new ResourceOptions(maxBytes)` still works).
- The README documents notifications, pop-outs, the tray, autostart, single instance and frameless windows.
- README, Linux uploads: Jolt's workaround for the WebKitGTK crash on `Blob` request bodies does not reach Web Workers. A worker must send
  strings or `ArrayBuffer`s (`await blob.arrayBuffer()`), never a `Blob` or a `FormData` holding one, because WebKitGTK aborts the whole
  process. A new Linux test checks that strings and bytes from a worker work over the in-process transport.

### Not verified
- How the new window and tray features look: the Windows shadow, snapping and dark tray menu; and a tray on a real KDE, XFCE or GNOME
  (with the extension) desktop. On Linux, minimizing and dragging need a window manager and were not run in the test containers.

## 0.2.5 - 2026-10-30

### Added
- **`Application.openExternal(URI)`**: opens an `http`, `https` or `mailto` address in the user's default browser or mail program
  (`ShellExecuteW` on Windows, `g_app_info_launch_default_for_uri` on Linux). Any other scheme, such as `file:`, throws
  `IllegalArgumentException`, because handing it to the shell could start a program. The future says whether the system accepted the
  request, not whether the page loaded. Useful from `onBlocked` to send a blocked link to the browser.
- **`AppOptions.withQuietNativeLogs(boolean)`** (Linux, off by default): hides a short list of known harmless GTK warnings, such as
  `GFileInfo created without standard::size` from the file chooser. Every other native message is still printed.
  `AppOptions` gained the component `quietNativeLogs`; the old three-argument constructor still works.

### Changed
- Linux: the process is named after the app id (`g_set_prgname`) before GTK starts, so on Wayland the window's `app_id` is your app id
  instead of `java` and the compositor can find the icon through `<appId>.desktop`.
- The README is shorter and simpler.


## 0.2.4 - 2026-10-29

### Added
- **File dialogs**: `WebWindow.dialogs()` returns `Dialogs`, the operating system's own open, save and choose-folder dialogs
  (`openFile`, `openFiles`, `saveFile`, `chooseFolder`), owned by the window. Each returns a `CompletableFuture` that completes with the
  real path the user chose, or with an empty result when they cancel. `FileDialogOptions` sets the title, the starting folder, a
  proposed or preselected file name and `FileFilter`s. Use them to read a file by its path, or to let the user pick where Java
  saves one. Windows uses the common item dialogs; Linux uses `GtkFileChooserNative`, which goes through the desktop portal
  when the app is sandboxed. `FileDialogOptions.withSize(width, height)` asks for a bigger dialog (Windows resizes it after it opens;
  on Linux a dialog with a size is GTK's own chooser instead of the portal's). Tested with the real dialogs on Windows and on Linux (X11
  and Wayland, in Docker).

- **`jolt.saveAs(data, options)`** in the page, after `window.enableSaveAs()` (optionally with the starting folder) in Java: opens the
  save dialog, then writes the string, bytes or `Blob` the page hands over to the file the user chose. The page learns only the file
  name. The data goes to a temporary file that replaces the target when complete, so a failed transfer leaves nothing half written.
  Channel names starting with `jolt.` are now reserved.

## 0.2.3 - 2026-10-28

### Added
- **Binary channels**: `WebWindow.openChannel(name[, ChannelOptions])` returns a `BinaryChannel`, a named two-way pipe for raw bytes
  between Java and the page (`jolt.channel(name)` in the page, with `onMessage`, `send`, `onOpen`, `onClose`). For video frames,
  audio, images, files and other data that is too big or too binary for `postMessage`. `ChannelOptions.newestOnly()` makes a slow
  page get only the newest message instead of a backlog. The transport is a loopback WebSocket that accepts only origins the
  window's `SecurityPolicy` trusts and a per-run secret. Tested against a plain WebSocket client and in real windows on Windows and on Linux (X11 and Wayland).
  On Linux the page must be shown with `loadResources`: WebKitGTK blocks the connection from a `loadHtml` page.
- `WindowOptions.withMinSize(width, height)`: the smallest size the user can resize the window to (Windows `WM_GETMINMAXINFO`,
  GTK geometry hints). `WindowOptions` gained the components `minWidth` and `minHeight`, so code that calls its constructor
  directly (rather than `defaults()` and the `with` methods) needs two more arguments. Checked on Windows and on Linux under X11.

## 0.2.2 - 2026-10-27

### Added
- `WindowOptions.withMaximized(boolean)` and `withCentered(boolean)`, and `WebWindow.maximize()`, `restore()` and `isMaximized()`.
  **New windows are now centered on the screen by default** (before, Windows cascaded them from the top-left). Use
  `withCentered(false)` to get the operating system's own placement back. Centering is ignored on Wayland, where the
  compositor decides, and when the window starts maximized.
- On Windows the native window is painted with `backgroundRgb` behind the web view, so a window no longer starts out white
  while the web view is still starting.
- Contract tests for a hidden window that is shown later, and for maximizing; `WindowPlacementTest` (Windows) reads back the
  window position, maximized state and painted background; an X11 test reads the centered position from GTK.

### Fixed
- **A window created hidden (`withVisible(false)`) and shown later stayed blank on Windows**: the web view was never told
  that its window had become visible. It is now, including when the window is shown maximized.

### Changed
- `WindowOptions` has two more record components (`maximized`, `centered`). Code that uses `WindowOptions.defaults()` and the
  `with...` methods is unaffected; code that calls the record's constructor directly has to pass them.
- `WebWindow` has three more methods; custom implementations of the interface (there are none in Jolt besides the two
  backends) have to add them.

## 0.2.1 - 2026-10-27

### Added
- **`jolt-credentials`**, a new optional module: `CredentialStore.open(service)` with `put`, `get` and `delete` keeps passwords and
  tokens in the operating system's keystore, through the FFM API (no JNI, no native code to build).
  - Windows: Credential Manager (`CredWriteW`, `CredReadW`, `CredDeleteW`). A secret is at most 2560 bytes in UTF-8.
  - Linux: the Secret Service through `libsecret` (GNOME Keyring, KWallet, KeePassXC).
  - `CredentialStore.open` throws `CredentialStoreUnavailableException` when the machine has no usable keystore (unsupported
    OS, no `libsecret`, no Secret Service, or no default keyring), so an application can fall back.
  - It does not depend on `jolt-core`.
  - Verified: Windows 11 against the real Credential Manager, and Linux (Ubuntu 24.04 in Docker) against gnome-keyring. Not verified: KWallet, KeePassXC, a locked keyring that prompts the user, other distributions.

### Changed
- README: new section on saving passwords and tokens, and the Maven and Gradle snippets use 0.2.1.

`jolt-core`, `jolt-win32` and `jolt-linux` have no code changes in this version; they are republished so all modules share a version.

## 0.2.0 - 2026-10-27

### Added
- `loadResources`: a page made of separate HTML, CSS and JS files (or a bundler's output) served from inside the web view with no
  socket, with a stable origin so `localStorage` survives restarts. `AppResources.classpath(...)` and `AppResources.directory(...)`.
- Java request handlers for the page's own requests (`fetch`, forms, htmx, JSON APIs).
- `Router`: method and path routing with path parameters, groups, filters and error handling.
- `multipart/form-data` uploads with a configurable size limit (`ResourceOptions`).
- On Linux, a page-side shim that rewrites `Blob` request bodies to bytes, because WebKitGTK aborts the process on them.

## 0.1.0 - 2026-10-26

First release: native windows with a web view on Windows (WebView2) and Linux (WebKitGTK 4.1), `loadUrl`, `loadHtml`, `eval`,
`addInitScript`, string messages between Java and the page, window icon, native title bar and background colours, and a
per-window security policy.
