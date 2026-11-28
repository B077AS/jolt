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

[Add Jolt to your project](#add-jolt-to-your-project) below opens your first window in a few lines.

For a complete app, see [Parsel](https://github.com/B077AS/parsel), a desktop mail client built on Jolt: Java for IMAP and SMTP,
a Vue page for the interface, one process, packaged for Windows and Linux. It is the main example of how a real Jolt app is put
together, and there is a [case study](https://jolt.williamsolari.com/parsel) on the Jolt site.

> **Status: early.** Both backends pass the same integration suite (Windows; Linux on X11 and Wayland in containers). macOS is
> not supported. If something is rough, please open an issue.

## Add Jolt to your project

Add the API and both backends; each one only activates on its own OS.

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <jolt.version>0.5.0</jolt.version>
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
implementation("io.github.b077as:jolt-core:0.5.0")
implementation("io.github.b077as:jolt-win32:0.5.0")
implementation("io.github.b077as:jolt-linux:0.5.0")
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

## Calling Java from the page

For request and response, attach an `Rpc` to the window: the page calls Java methods with `jolt.call` and gets promises back, and
Java pushes events to the page.

```java
record NewNote(String text) {}

Rpc rpc = Rpc.create()
        .on("notes.list", call -> store.list())
        .on("notes.add", NewNote.class, note -> store.add(note.text()));
rpc.attach(window);                          // before the page loads
rpc.emit("notes.changed", store.count());    // Java -> page
```

```js
const notes = await jolt.call('notes.list');
await jolt.call('notes.add', { text: 'Buy milk' });
jolt.onEvent('notes.changed', (count) => (badge.textContent = count));
```

Errors, cancellation, progress, streaming results, interceptors, annotated controllers and generated TypeScript types are in
[docs/rpc.md](docs/rpc.md).

## Everything else

| I want to... | Read |
| --- | --- |
| Serve a multi-file frontend, answer requests from Java (forms, htmx, `fetch`), handle uploads | [docs/frontend.md](docs/frontend.md) |
| Send binary data between the page and Java | [Channels](docs/rpc.md#binary-data-channels) |
| Set the window's size and position, go frameless with a custom title bar, pick files, accept dropped files, show notifications, open more windows | [docs/windows.md](docs/windows.md) |
| Add a tray icon, taskbar extras, a menu bar, a context menu, a global hotkey, print to PDF | [docs/desktop-integration.md](docs/desktop-integration.md) |
| Know when a page fails or crashes, ask before closing, save on shutdown, handle downloads | [docs/lifecycle.md](docs/lifecycle.md) |
| Use Spring Boot | [docs/spring-boot.md](docs/spring-boot.md) |
| Package the app, or make installs update themselves | [docs/packaging.md](docs/packaging.md) |
| Keep passwords and tokens in the OS keystore | [below](#saving-passwords-and-tokens-jolt-credentials) |

## Security

The page can call everything you register with `onMessage`, so each window has a `SecurityPolicy`. The default,
`SecurityPolicy.standard()`, trusts your own content plus origins you list, and blocks the rest:

| Protection | When on | Switch |
| --- | --- | --- |
| Navigation lock | Links, redirects, frames and `loadUrl` to an untrusted origin are cancelled | `withLockNavigation` |
| Message check | Messages from an untrusted page never reach `onMessage` | `withRestrictMessages` |
| New windows | `window.open` and `target=_blank` are refused | `withBlockNewWindows` |
| Permissions | Camera, microphone, location and similar are denied | `withDenyPermissions`; allow microphone/camera for your own pages with `withGrantedPermissions(Permission.MICROPHONE)` |
| Bridge in frames | `window.jolt` exists only in the top-level page | `withBridgeInSubframes` |
| Downloads | Refused unless the window has an [`onDownload`](docs/lifecycle.md#downloads) handler | `withBlockDownloads` |
| Content-Security-Policy | Your pages load nothing from other hosts; with `loadResources`, no inline scripts either | [`withContentSecurityPolicy`](docs/frontend.md#content-security-policy) |

```java
WindowOptions.defaults().withSecurity(SecurityPolicy.standard().withTrustedOrigins("http://localhost:5173"));
window.setSecurity(window.security().withLockNavigation(false));       // change it any time
window.onBlocked(event -> {                                            // called after something was blocked
    if (event.kind() == BlockedEvent.Kind.NAVIGATION) openInSystemBrowser(event.url());
});
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
| `jolt-spring-boot-starter` | Run a Spring Boot app in a window |

`Application.run` turns the calling thread into the UI thread and runs the platform message loop; other threads go through `dispatch`.
Windows calls WebView2's COM interfaces through their vtables with FFM, Linux uses GTK 3 and WebKitGTK the same way.

See the [changelog](CHANGELOG.md) for what each version added.
