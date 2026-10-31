# Changelog

All published versions of Jolt, newest first. Every module (`jolt-core`, `jolt-win32`, `jolt-linux`, `jolt-credentials`) is
released with the same version number. Jolt is early software (version 0.x): APIs can still change between versions, and
this file says when they do.

The latest version is the one on [Maven Central](https://central.sonatype.com/artifact/io.github.b077as/jolt-core).

## Unreleased

### Added
- **System notifications**: `Application.notify(Notification)` shows a toast (WinRT `Windows.UI.Notifications` on Windows, the
  `org.freedesktop.Notifications` D-Bus service on Linux). On Windows the first call registers `appId` as a display name under
  `HKCU\Software\Classes\AppUserModelId`; nothing is installed. It completes with `false` when Windows has notifications turned
  off for the user or the app. `Notification.withOnClick`, `withButton` (up to 5) and `withReply` (a text field with a send button,
  Windows only: the Linux specification has no text input) run on the UI thread while the application is running.
  `WebWindow.enableNotifications(app)` lets trusted pages call `jolt.notify(title, body)` (a promise of whether the system showed it;
  at most 5 per 10 seconds per window). The page can pass `{ onclick, buttons: [{ label, onclick }], reply: { label,
  placeholder, onreply } }` as a third argument; the callbacks run in the page when the user uses the notification.

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
