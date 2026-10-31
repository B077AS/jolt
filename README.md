<h1 align="center">
  <img src=".github/icon.png" alt="Jolt icon" width="96"><br>
  Jolt
</h1>

<h3 align="center">
  Desktop apps with web UIs, in plain Java.<br>
  No Electron. No JNI. No native code to compile.
</h3>

<p align="center">
  Open a native window, load a web page, and talk to it from Java.<br>
  Your code and your page run in <b>one process</b>, with no HTTP server in between.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.b077as/jolt-core"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.b077as/jolt-core?label=Maven%20Central&color=C71A36"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white">
  <img alt="Foreign Function & Memory API" src="https://img.shields.io/badge/FFM%20API-no%20JNI-5382A1">
  <img alt="WebView2 | WebKitGTK" src="https://img.shields.io/badge/WebView2%20%7C%20WebKitGTK-system%20web%20view-0078D4">
  <img alt="Windows | Linux" src="https://img.shields.io/badge/Windows%20%7C%20Linux-X11%20%2B%20Wayland-4CAF50">
  <img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-blue">
  <img alt="Status: early" src="https://img.shields.io/badge/status-early-E8A200">
</p>

---

```java
Application app = Application.create(AppOptions.of("com.example.notes"));
app.run(() -> {
    WebWindow window = app.createWindow(WindowOptions.defaults().withTitle("Notes").withSize(900, 600));
    window.onMessage(text -> window.postMessage("You said: " + text));  // page -> Java -> page
    window.loadHtml("<button onclick=\"jolt.postMessage('hi')\">Say hi</button>");
});
```

That is a complete app: a native window, a real web view, and messages in both directions. The page draws the interface.
Java does everything a page may not: files, databases, native libraries, long-running work.

## Why Jolt

- **Tiny.** No bundled browser. Jolt uses the web view the OS already has (WebView2 on Windows, WebKitGTK on Linux). A
  packaged app with its own Java runtime is about 32 MB.
- **No native build step.** Built on Java 25's Foreign Function & Memory API. The only binary shipped is Microsoft's
  `WebView2Loader.dll`.
- **Any frontend.** Plain HTML, React, Vue, Svelte, htmx: anything a browser can show.
- **One jar, two platforms.** Windows 10/11 and Linux (X11 and Wayland).
- **Secure by default.** Only your own pages can reach your Java code. See [Security](#security).

| | Jolt | Electron | Tauri | Wails | JavaFX WebView |
| --- | --- | --- | --- | --- | --- |
| Backend language | Java | JavaScript | Rust | Go | Java |
| Ships its own browser | No | Yes | No | No | Yes, a WebKit build inside JavaFX |
| Native toolchain to build | None | None | Rust | Go | None |

## Try it

You need JDK 25 and Maven 3.9+. On Linux also `sudo apt install libgtk-3-0 libwebkit2gtk-4.1-0`.

```powershell
git clone https://github.com/B077AS/jolt.git
cd jolt
mvn install -DskipTests
mvn -pl examples/notes-app -am install -DskipTests
java --enable-native-access=ALL-UNNAMED -cp "examples/notes-app/target/package/*" io.github.b077as.jolt.examples.notes.NotesApp
```

The [notes example](examples/notes-app) is a small complete app: a Java data layer, JSON requests between Java and the page,
light and dark themes, and an optional React frontend (`examples/notes-app/frontend`).

> **Status: early.** Both backends pass the same integration suite (Windows; Linux on X11 and Wayland in containers). macOS is
> not supported. If something is rough, please open an issue.

## Add Jolt to your project

Add the API and both backends; each one only activates on its own OS.

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <jolt.version>0.2.5</jolt.version>
</properties>

<dependencies>
  <dependency>
    <groupId>io.github.b077as</groupId>
    <artifactId>jolt-core</artifactId>
    <version>${jolt.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.b077as</groupId>
    <artifactId>jolt-win32</artifactId>
    <version>${jolt.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.b077as</groupId>
    <artifactId>jolt-linux</artifactId>
    <version>${jolt.version}</version>
  </dependency>
</dependencies>
```

Gradle:

```kotlin
implementation("io.github.b077as:jolt-core:0.2.5")
implementation("io.github.b077as:jolt-win32:0.2.5")
implementation("io.github.b077as:jolt-linux:0.2.5")
```

Run Java with `--enable-native-access=ALL-UNNAMED`, or put `Enable-Native-Access: ALL-UNNAMED` in your jar manifest
(`maven-jar-plugin`, `<manifestEntries>`), otherwise Java prints a warning on every start.

Requirements: JDK 25; on Windows the [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)
(preinstalled on Windows 11); on Linux GTK 3 and WebKitGTK 4.1 (`libgtk-3-0 libwebkit2gtk-4.1-0`).

## The basics

```java
Application app = Application.create(AppOptions.of("com.example.hello"));
try {
    app.run(() -> {
        WebWindow window = app.createWindow(WindowOptions.defaults().withTitle("Hello").withSize(600, 400));
        window.onMessage(text -> window.postMessage("Java received: " + text));
        window.loadHtml("""
                <button onclick="jolt.postMessage('ping')">Ping Java</button>
                <p id="out"></p>
                <script>jolt.onMessage(m => document.getElementById('out').textContent = m)</script>
                """);
    });
} finally {
    app.close();
}
```

- `AppOptions.of(id)` names your app. It picks a per-user data folder (`%LOCALAPPDATA%\<id>` or `~/.local/share/<id>`).
- `app.run(onStart)` blocks until the app ends (last window closed, or `quit()`). Create windows inside `onStart`.
- `window.jolt` is injected into every page: `jolt.postMessage(text)` sends to Java, `jolt.onMessage(cb)` receives.
- `window.eval(script)` runs a script and returns a `CompletableFuture<String>` with the JSON result.
  `window.addInitScript(script)` runs before the page's own scripts.
- Every `Application` and `WebWindow` method may be called from any thread. Message listeners run on the **UI thread**, so
  never block there (for example by calling `.get()` on an `eval` result) and hand slow work to another thread.
- To end the app, let `main` return rather than calling `System.exit`.
- `app.openExternal(URI)` opens an `http`, `https` or `mailto` address in the user's browser or mail program (since 0.2.5).

### Calling Java functions from the page

Messages are plain strings. For real apps, add a tiny request/response layer on top: the page sends
`{"id": 1, "method": "notes.add", "params": {...}}` and Java answers `{"id": 1, "result": ...}` or `{"id": 1, "error": "..."}`.
The notes example has both halves (`Rpc.java` and `ui/index.html`), about 40 lines each:

```java
new Rpc(window)
    .on("notes.list", params -> store.list())
    .on("notes.add",  params -> store.add(params.get("text").getAsString()));
```

```js
const note = await call('notes.add', { text: 'Buy milk' });
```

Copy that pattern into your project. It works with any JSON library.

### Binary data: channels

For big or binary data (video frames, images, files) open a named two-way channel. No base64, no JSON (since 0.2.3).

```java
BinaryChannel frames = window.openChannel("frames");   // open it before the page loads
frames.onMessage(bytes -> handle(bytes));
frames.send(ByteBuffer.wrap(pixels));                  // never blocks; returns false if refused
```

```js
const frames = jolt.channel("frames");
frames.onMessage(data => { /* ArrayBuffer */ });
frames.send(new Uint8Array([1, 2, 3]));
```

- The page connects and reconnects by itself. `onOpen`, `onClose` and `isOpen()` tell you the state.
- `ChannelOptions.newestOnly()` keeps only the latest message for a slow page (live video); `withMaxQueuedBytes` bounds the queue.
- Callbacks run on a thread of their own, not the UI thread.
- A channel is a WebSocket on `127.0.0.1`, accepted only from origins your [`SecurityPolicy`](#security) trusts and with a
  per-run secret.
- **On Linux, use `loadResources` (not `loadHtml`) for pages that open channels**: WebKitGTK blocks the connection otherwise.

### File dialogs

A page never sees a file's path. Use the OS dialogs through the window (since 0.2.4):

```java
Dialogs dialogs = window.dialogs();
dialogs.saveFile(FileDialogOptions.defaults()
                .withTitle("Export report").withFileName("report.pdf")
                .withFilters(FileFilter.of("PDF documents", "pdf")))
        .thenAccept(chosen -> chosen.ifPresent(path -> writeReport(path)));   // empty if cancelled

dialogs.openFile(FileDialogOptions.defaults());      // also openFiles(...) and chooseFolder(...)
```

Each call returns a `CompletableFuture` at once. Cancelling gives an empty result, not an error. `withSize(w, h)` asks for a
bigger dialog (a request: the system owns it).

To let the **page** save a file, call `window.enableSaveAs()` in Java and `jolt.saveAs` in the page:

```js
const saved = await jolt.saveAs(blobOrBytesOrText, { name: "report.pdf", filters: [{ name: "PDF", extensions: ["pdf"] }] });
// { name, size } or null if cancelled; the page never learns the path
```

The data is written to a temporary file and moved into place when complete. Only trusted origins may use it, and on Linux the page
must come from `loadResources`.

### More than one window (pop-outs)

`app.createWindow(...)` can be called as often as you like (on the UI thread), and `app.windows()` lists the windows that are open.
A page can ask for another window, for example an email shown in its own window, but it never opens one itself: your Java code
decides.

```java
main.enableOpenWindow(app, request -> {                          // runs on the UI thread, for each request from a page
    if (!request.path().startsWith("/message/")) return null;   // null refuses; the page's promise resolves false
    WebWindow popout = request.createWindow(app);                // title and size come from the page's suggestions
    popout.loadResources(resources, router,
            ResourceOptions.defaults().withStartPath(request.path()));   // the new window opens on /message/42
    return popout;
});
```

```js
const opened = await jolt.openWindow("/message/42", { key: "message-42", title: "Re: lunch", width: 900, height: 700 });
// true: a window opened, or the one with that key was brought to the front. false: the app refused.
```

- **`key`** stops duplicates: asking again for the same key focuses the window that is already open (your opener is not called).
- **`path`** must be an address inside the app (one leading `/`); `//host`, `https://...` and the like are rejected.
- The window you return gets `jolt.openWindow` and `jolt.broadcast` too, with the same opener; refuse inside the opener (look at
  `request.opener()`) if pop-outs must not open more windows. At most 10 requests per window per 10 seconds.
- The request is yours to check: `path`, `key`, `title` and the sizes come from the page. `request.options()` has no owner; use
  `.withOwner(request.opener())` if the pop-out should stay in front of its window and close with it. Without an owner it is an
  independent window.
- **Talking between windows**: `jolt.broadcast(text)` goes to every other window that has called `enableOpenWindow`, and
  `const stop = jolt.onBroadcast(text => ...)` receives it (a window never gets its own broadcasts; `text` is limited to 16 K characters).
  To refresh the message list after a reply, broadcast a short event such as `"message-42:read"` and let each window fetch what it needs.
- From Java: `window.focus()` brings a window to the front (restoring or showing it), `WindowOptions.withOwner(window)` makes a window
  belong to another one.

## The frontend

Your page is normal HTML, CSS and JavaScript. The web view is Chromium (Windows) or WebKit (Linux), so write for both.

- **Development:** point the window at your dev server and let it do hot reload:

  ```java
  window.setSecurity(window.security().withTrustedOrigins("http://localhost:5173"));
  window.loadUrl("http://localhost:5173");
  ```

  Or serve a folder from disk with `loadResources(AppResources.directory(Path.of("ui")))`; files are re-read on every request.
- **Release:** put the built page in `src/main/resources/ui` and serve it from the jar:

  ```java
  window.loadResources(AppResources.classpath("ui"));
  ```

  Separate HTML, CSS and JS files work, relative URLs and ES modules too, and the origin is stable so `localStorage` survives
  restarts. A single self-contained HTML file (Vite with `vite-plugin-singlefile`) can use `loadHtml` instead.

`examples/notes-app/frontend` is the notes app in React, TypeScript, Vite and Tailwind. `src/jolt.ts` is the typed bridge
(`call<T>(method, params)`), `src/api.ts` has one function per Java handler. Build it with `npm install; npm run build`, then run
the example with `"-Dui.file=examples/notes-app/frontend/dist/index.html"`, or with `"-Dui.url=http://127.0.0.1:5173"` against
`npm run dev`. (PowerShell splits `-D` flags at the dot, so keep the quotes.) Only React has been run; other frameworks go through
the same Vite setup.

## Serving files and answering requests from Java

`loadResources` shows a multi-file page and lets Java answer its requests (`fetch`, forms, htmx `hx-get`/`hx-post`, JSON APIs).
Files are served **inside the web view**, with no socket. The origin is `https://jolt.localhost` on Windows and `jolt://app` on
Linux; use relative URLs.

```java
window.loadResources(AppResources.classpath("ui"), request -> switch (request.path()) {
    case "/notes" -> AppResponse.html(renderNotes());
    case "/notes/add" -> {
        notes.add(request.formValue("text"));
        yield AppResponse.html(renderNotes());
    }
    default -> null;                                  // null: let the files answer
});
```

For more than a few routes use `Router`, which is a `RequestHandler`:

```java
Router router = Router.create()
    .get("/notes/{id}", request -> AppResponse.html(pages.note(request.param("id"))))
    .post("/notes", request -> { notes.add(request.formValue("text")); return AppResponse.redirect("/notes"); })
    .group("/admin", admin -> admin
        .before(request -> loggedIn(request) ? null : AppResponse.redirect("/login"))
        .get("/users", request -> AppResponse.html(pages.users())))
    .onError((request, error) -> AppResponse.html(pages.error(error)).withStatus(500));
window.loadResources(AppResources.classpath("ui"), router);
```

- Patterns: `{id}` captures one segment, `{*rest}` the rest. A plain segment beats `{id}`, which beats `{*rest}`.
- A request no route matches falls through to your files. A wrong method on a known path gets 405 with `Allow`.
- Handlers run on worker threads. An exception becomes a bare 500 and the details go to the log, never to the page.
- Escape anything user-supplied before putting it in HTML, and keep `GET` handlers free of side effects.

**Uploads.** `multipart/form-data` works with plain forms, `fetch` and htmx. Read it with `request.parts()` / `request.part(name)`:

```java
AppRequest.Part file = request.part("document");
if (file != null && !file.filename().isEmpty()) {
    Files.write(uploads.resolve(UUID.randomUUID() + ".bin"), file.content());   // choose the stored name yourself
}
```

Treat uploaded files as hostile: `filename()` and `contentType()` come from the user's machine. Bodies are held in memory, 8 MB
by default (413 above that); raise it with `ResourceOptions.defaults().withMaxRequestBytes(...)` (up to 1 GB). On Linux, Jolt works
around a WebKitGTK crash on `Blob` request bodies by turning them into bytes inside the page; a synchronous `XMLHttpRequest` with a
`Blob` throws `NotSupportedError`. That workaround does not reach Web Workers: on Linux a worker must send strings or
`ArrayBuffer`s (`await blob.arrayBuffer()`), never a `Blob` or a `FormData` holding one, because WebKitGTK aborts the whole process.

**Loopback fallback.** The in-process transport needs WebKitGTK 2.40+ on Linux. Older systems fall back to a guarded server on
`127.0.0.1` automatically (per-run CSRF token, `Origin`/`Host`/`Sec-Fetch-Site` checks, no CORS). You can also ask for it, for
example to open the app in a normal browser: `window.loadResources(resources, handler, 48123)`.

## Window look and placement

The OS draws the icon and title bar, so you set them from Java, at creation or later:

```java
WindowOptions options = WindowOptions.defaults()
        .withSize(1200, 780)
        .withMinSize(600, 400)                   // smallest size the user can drag to
        .withIcon(iconBytes)                     // multi-size .ico (best) or PNG
        .withTitleBarScheme(ColorScheme.DARK)
        .withBackgroundColor(0x0F1117)           // behind the page until it paints: no white flash
        .withMaximized(false)
        .withCentered(true)                      // the default
        .withVisible(false);                     // call window.show() when the page is ready

window.setTitleBarColors(0xF4F5FA, 0x1A1D29);    // exact colours, Windows 11 only
window.maximize(); window.restore(); window.isMaximized();
```

- Use an `.ico` with 16, 24, 32, 48 and 256 px images: Windows picks the matching size instead of scaling one bitmap. On Linux the
  largest image is used.
- Start hidden and call `show()` when the page sends "ready" so the window appears with its content already drawn.
- On Wayland the compositor decides placement, so `withCentered` is ignored and the icon comes from the app's `.desktop` file.
  Jolt names the process after your app id, which is what lets the compositor find it (since 0.2.5).
- On Linux, GTK sometimes prints harmless warnings to the console (for example from its file chooser).
  `AppOptions.of(id).withQuietNativeLogs(true)` hides that known list and leaves other messages alone (since 0.2.5).
- A fully custom title bar: see [Frameless windows](#frameless-windows) below.
- Developer tools: `WindowOptions.defaults().withDevTools(true)`, then right-click, Inspect, or `window.openDevTools()`.

### Frameless windows

`WindowOptions.defaults().withFrameless(true)` removes the operating system's title bar and border, so the page draws its own:

```html
<header data-jolt-drag>                       <!-- drag here to move the window; double-click maximizes or restores -->
  <span>Parsel</span>
  <button onclick="jolt.frame.minimize()">–</button>
  <button onclick="jolt.frame.toggleMaximize()">☐</button>
  <button onclick="jolt.frame.close()">✕</button>
</header>
```

- `data-jolt-drag` marks the draggable area. Buttons, links and form fields inside it keep working; add `data-jolt-no-drag` to
  exclude anything else. (CSS `app-region: drag` is not used: WebKitGTK does not support it.)
- The window can be resized from thin invisible handles along its edges and corners, which Jolt adds to the page.
- `jolt.frame.close()` closes the window as the close button would, so `setCloseBehavior(HIDE)` applies to it too.
- On Windows the window keeps its sizing frame (the page is given the whole window), which is what lets Windows draw the shadow and
  snap it (Win+arrows); the tests check the layout, not how the shadow and snapping look. Linux draws no shadow and snaps only as your
  window manager does; on Wayland, moving and resizing needs a compositor that accepts the request.
- Frame commands only work on windows created `frameless`, and only for pages the `SecurityPolicy` trusts like any other message.

## Tray icon, close to tray, start with the computer

A mail client or chat app usually lives in the tray, hides instead of quitting when its window is closed, and can start with the
computer. These are separate pieces, so you choose which to use, and each can be changed while the app runs.

```java
Application app = ...;
WebWindow main = ...;

if (app.isTraySupported()) {                                   // Windows: always. Linux: needs a tray host (see below)
    Tray tray = app.createTray(iconBytes, "Parsel");           // call on the UI thread
    tray.onClick(main::focus);                                 // primary click: bring the window back
    tray.setMenu(TrayMenu.of(
            TrayMenu.action("Open Parsel", main::focus),
            TrayMenu.separator(),
            TrayMenu.check("Close to tray", closeToTray, on -> setCloseToTray(on)),
            TrayMenu.check("Start with the computer", autostart, on -> setAutostart(on)),
            TrayMenu.separator(),
            TrayMenu.action("Quit", app::quit)));
}
```

```java
main.setCloseBehavior(closeToTray ? CloseBehavior.HIDE : CloseBehavior.CLOSE);    // takes effect on the next click of the close button

Autostart autostart = app.autostart();
autostart.enable(List.of(exePath, "--minimized"));     // start with the computer, minimized...
autostart.enable(List.of(exePath));                    // ...or not: calling it again replaces the entry
autostart.disable(); autostart.isEnabled();
```

- **Menus are immutable.** To tick a box or change a label, build the menu again and call `tray.setMenu(...)`; `tray.setIcon` and
  `tray.setTooltip` do the same for the icon and its text (for example "3 unread"). Menu callbacks run on the UI thread.
- **Start minimized** is an argument you choose: put `--minimized` in the autostart command, and in `main` create the window with
  `withVisible(false)` (and a tray) when `args` contains it. Only hide at startup if `app.isTraySupported()`, or the user cannot reach the app.
- **Close to tray needs a tray.** `CloseBehavior.HIDE` only hides the window; `focus()` or `show()` brings it back, `close()` and `app.quit()`
  still really close it. Check `isTraySupported()` before choosing `HIDE`.
- **The command**: give the launcher of your installed or packaged app (on Windows a quoted path in the Run key, on Linux the `Exec=`
  line of `~/.config/autostart/<appId>.desktop`). `Autostart.currentCommand(extraArgs)` describes the running process, which is fine for
  `java -jar` while developing.
- **Windows**: the tray menu is a native menu; Jolt asks Windows to draw it in dark mode when the system is dark, using an undocumented
  call that is skipped if it is missing. It supports checks, disabled entries, separators and submenus, but not icons. If Explorer restarts, the icon is put back.
- **Linux**: the tray is a StatusNotifierItem with a `dbusmenu` menu, which KDE Plasma, XFCE, Cinnamon, MATE, Budgie and others show.
  Stock GNOME does not show tray icons unless the user installed the AppIndicator extension, so `isTraySupported()` is `false` there.
  If the tray host restarts while the app runs, the icon is not registered again.
- **One copy only**: a tray app that starts at sign-in should not start twice.

```java
public static void main(String[] args) {
    AppOptions options = AppOptions.of("com.example.parsel");
    SingleInstance instance = SingleInstance.acquire(options, List.of(args), otherArgs -> app.dispatch(() -> main.focus()));
    if (!instance.isPrimary()) {
        return;                       // the running copy was told (and receives this copy's arguments); nothing more to do
    }
    ...
}
```

`SingleInstance` takes a lock file in the data directory (released by the system if the app dies) and talks to the running copy over a
Unix domain socket that only the same user can reach. The callback runs on a thread of its own: use `app.dispatch`.

## Security

The page can call everything you register with `onMessage`, so each window has a `SecurityPolicy`. The default,
`SecurityPolicy.standard()`, trusts your own content plus origins you list, and blocks the rest:

| Protection | When on | Switch |
| --- | --- | --- |
| Navigation lock | Links, redirects, frames and `loadUrl` to an untrusted origin are cancelled | `withLockNavigation` |
| Message check | Messages from an untrusted page never reach `onMessage` | `withRestrictMessages` |
| New windows | `window.open` and `target=_blank` are refused | `withBlockNewWindows` |
| Permissions | Camera, microphone, location and similar are denied | `withDenyPermissions` |
| Bridge in frames | `window.jolt` exists only in the top-level page | `withBridgeInSubframes` |

```java
WindowOptions.defaults().withSecurity(SecurityPolicy.standard().withTrustedOrigins("http://localhost:5173"));
window.setSecurity(window.security().withLockNavigation(false));       // change it any time
window.onBlocked(event -> openInSystemBrowser(event.url()));           // called after something was blocked
```

- `loadUrl` to another site is blocked unless you trust its origin.
- Trusting an origin gives that site your handlers. List only sites you control, and still validate every parameter.
- WebView2 reports which frame sent a message; WebKitGTK does not, so on Linux the check uses the top-level page. If you turn the
  navigation lock off on Linux, keep the bridge out of frames (the default).
- In the page, set user-supplied text with `textContent`, never `innerHTML`.

## Saving passwords and tokens (`jolt-credentials`)

An optional module (since 0.2.1) that keeps secrets in the OS keystore, with no window needed:

```java
CredentialStore store = CredentialStore.open("com.example.mail");
store.put("alice@example.com", password);
Optional<String> saved = store.get("alice@example.com");
store.delete("alice@example.com");
```

Add `io.github.b077as:jolt-credentials:${jolt.version}`. Windows uses the Credential Manager (secrets up to 2560 bytes in UTF-8).
Linux uses the Secret Service through `libsecret` (`libsecret-1-0`) and needs a running, unlocked keyring. `open` throws
`CredentialStoreUnavailableException` when there is none, so catch it and fall back. Call it off the UI thread: it can block while
the user unlocks the keyring.

## Package it

`jpackage` turns your jars into a double-clickable app that bundles its own Java runtime:

```powershell
mvn -pl examples/notes-app -am install
jpackage --type app-image --name JoltNotes `
  --input examples\notes-app\target\package `
  --main-jar notes-app-0.1.0-SNAPSHOT.jar --main-class io.github.b077as.jolt.examples.notes.NotesApp `
  --java-options "--enable-native-access=ALL-UNNAMED" `
  --add-modules "java.base,java.logging,jdk.unsupported" `
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress zip-6" `
  --dest dist
```

That gives about 32 MB (about 120 MB without `--add-modules` and `--jlink-options`). Add the modules your own dependencies need.
This was run on Windows; the same command should work on Linux but has not been tried. Code signing is up to you.

## When something goes wrong

| Symptom | Likely cause |
| --- | --- |
| `WebView2 Runtime is not installed` | Install the WebView2 Runtime (Windows). |
| `GTK 3 / WebKitGTK 4.1 could not be loaded` | Install `libgtk-3-0` and `libwebkit2gtk-4.1-0`. |
| `GTK could not open a display` | No `DISPLAY` or `WAYLAND_DISPLAY`: a server or container without a display. |
| Warning about native access at start | Add `--enable-native-access=ALL-UNNAMED`. |
| `Could not find or load main class .url=...` | PowerShell split `-Dui.url=...` at the dot. Quote it. |
| Window stays blank | Open developer tools and read the console. With `loadUrl`, check the dev server is running. |
| `call(...)` never resolves | Your handler never replied, or the page was reloaded. |
| The window freezes | Something slow ran in a message listener. Move it to a worker thread. |
| Old icon in the taskbar | Windows caches it. Unpin and re-pin, or restart Explorer. |

## Build Jolt itself

```powershell
mvn install            # builds everything and runs all tests (they open real windows)
```

| Module | What |
| --- | --- |
| `jolt-core` | Public API, platform SPI, shared helpers, the page-side bridge script |
| `jolt-win32` | Windows backend: Win32 window and WebView2, driven through COM vtables from Java |
| `jolt-linux` | Linux backend: GTK 3 and WebKitGTK 4.1 over FFM |
| `jolt-credentials` | OS keystore for passwords and tokens |
| `examples/hello-window` | Smallest app; `--smoke` runs a scripted round trip |
| `examples/notes-app` | A complete small app, plus a React frontend |

How it works, briefly: `Application.run` turns the calling thread into the UI thread and runs the platform message loop; other
threads go through `dispatch`. On Windows, WebView2's COM interfaces are called through their vtables with FFM (slot numbers are
generated from Microsoft's header, because a wrong slot crashes the JVM), and callback interfaces are Java objects backed by
upcall stubs. `loadResources` sits on one transport-independent class that serves files, calls your handler and maps failures to
HTTP errors, behind WebView2's request interception, a WebKitGTK URI scheme, or the loopback server.

See the [changelog](CHANGELOG.md) for what each version added.
