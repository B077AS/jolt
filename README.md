<h1 align="center">
  <img src=".github/icon.png" alt="Jolt icon" width="96"><br>
  Jolt
</h1>

<h3 align="center">
  Desktop apps with web UIs, in plain Java.<br>
  No Electron. No JNI. No native code to compile.
</h3>

<p align="center">
  Open a native window, load any web page, and talk to it from Java.<br>
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

<p align="center">
  <b>~32 MB</b> packaged app with its own Java runtime &nbsp;|&nbsp;
  <b>0</b> lines of native code to build &nbsp;|&nbsp;
  <b>1</b> jar for Windows and Linux &nbsp;|&nbsp;
  <b>any</b> frontend
</p>

---

## Ship a desktop app with the stack you already know

```java
Application app = Application.create(AppOptions.of("com.example.notes"));
app.run(() -> {
    WebWindow window = app.createWindow(WindowOptions.defaults().withTitle("Notes").withSize(900, 600));
    window.onMessage(text -> window.postMessage("You said: " + text));  // page -> Java -> page
    window.loadHtml("<button onclick=\"jolt.postMessage('hi')\">Say hi</button>");
    window.eval("1 + 2").thenAccept(json -> System.out.println(json));  // prints 3
});
```

That is a complete app. A native window, a real web view, two-way messages, and a script round trip, in eight lines of
Java.

```
 +--------------------------- one process ----------------------------+
 |  Your Java code                         The window                  |
 |  (files, databases, network, logic) <-> a web page (HTML/CSS/JS)    |
 |              window.postMessage / onMessage                         |
 +---------------------------------------------------------------------+
```

The page draws the interface and handles clicks. Java does everything a web page is not allowed to do: read and write
files, talk to a database, call native libraries, keep long-running state.

## Why Jolt

- **Tiny apps.** There is no bundled browser. Jolt uses the web view your operating system already has (WebView2 on
  Windows, WebKitGTK on Linux), so a packaged app with its own Java runtime is about 32 MB.
- **No native build step.** Jolt is built on Java 25's Foreign Function & Memory API. No JNI, no C toolchain, nothing to
  compile. The only binary shipped is Microsoft's redistributable `WebView2Loader.dll`.
- **Bring any frontend.** Plain HTML, React, Vue, Svelte: anything that builds to a web page. The repo includes a React,
  TypeScript and Tailwind example with hot reload.
- **Bring any Java library.** Your backend is plain Java. Use SQLite, H2, an HTTP client, or a native library the usual way.
- **One jar, two platforms.** Windows 10/11 and Linux (X11 and Wayland), from the same dependency list.
- **Secure by default.** Pages can only reach your Java code if you trust them, and you can change the rules at any time.
  See [Security](#security).
- **Thread safe.** Every `WebWindow` and `Application` method can be called from any thread.

## Jolt next to the alternatives

| | Jolt | Electron | Tauri | JavaFX WebView |
| --- | --- | --- | --- | --- |
| Backend language | Java | JavaScript (Node.js) | Rust | Java |
| Ships its own browser | No, uses the system web view | Yes, Chromium | No, uses the system web view | Yes, a WebKit build inside JavaFX |
| Native toolchain to build | None | None | Rust | None |
| Use your Java libraries directly | Yes | No | No | Yes |

## Try it in 60 seconds

You need JDK 25 and Maven 3.9 or newer. On Linux, also `sudo apt install libgtk-3-0 libwebkit2gtk-4.1-0`.

```powershell
git clone https://github.com/B077AS/jolt.git
cd jolt
mvn install -DskipTests
```

Then run the smallest example, a window with a scripted round trip:

```powershell
$r = (Get-Location).Path
java --enable-native-access=ALL-UNNAMED `
  -cp "$r\examples\hello-window\target\classes;$r\jolt-core\target\classes;$r\jolt-win32\target\classes;$r\jolt-linux\target\classes" `
  io.github.b077as.jolt.examples.hello.HelloWindow
```

Want a real app? The [notes example](examples/notes-app) is a complete small program with a Java data layer, JSON requests
between Java and the page, light and dark themes, and an optional React frontend.

> **Status: early.** Both backends pass the same shared integration suite (Windows, and Linux on X11 and Wayland in
> containers). macOS is not supported. If you try Jolt and something is rough, please open an issue. It helps a lot.

## Add Jolt to your project

Jolt is published on Maven Central under the group id `io.github.b077as`. Add the API and both backends. Each backend
only activates on its own operating system, so one jar runs on both.

### Maven

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <jolt.version>0.2.2</jolt.version>
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

Java prints a warning on every start unless native access is enabled. Put it in your jar manifest so it is always on:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-jar-plugin</artifactId>
      <version>3.4.2</version>
      <configuration>
        <archive>
          <manifestEntries>
            <Enable-Native-Access>ALL-UNNAMED</Enable-Native-Access>
          </manifestEntries>
        </archive>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Or pass `--enable-native-access=ALL-UNNAMED` on the `java` command line.

### Gradle

```kotlin
dependencies {
    implementation("io.github.b077as:jolt-core:0.2.2")
    implementation("io.github.b077as:jolt-win32:0.2.2")
    implementation("io.github.b077as:jolt-linux:0.2.2")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
```

If you only target one operating system, depend on `jolt-core` and that system's backend only.

> **Building from source or using a version that is not on Maven Central yet?** Run `mvn install -DskipTests` in this
> repository and use `0.1.0-SNAPSHOT` as the version. It is installed into your local Maven repository.

## Contents

1. [What works today](#what-works-today)
2. [Requirements](#requirements)
3. [Your first window](#your-first-window)
4. [Talking between the page and Java](#talking-between-the-page-and-java)
5. [The frontend](#the-frontend)
6. [Using what Java is good at](#using-what-java-is-good-at)
7. [Run it](#run-it)
8. [Window icon, title bar and colours](#window-icon-title-bar-and-colours)
9. [Change the app icon](#change-the-app-icon)
10. [Package it](#package-it)
11. [Things to know](#things-to-know)
12. [Multi-file pages, request handlers and uploads](#multi-file-pages-request-handlers-and-uploads)
13. [Security](#security)
14. [Saving passwords and tokens](#saving-passwords-and-tokens-jolt-credentials)
15. [Layout of a Jolt app](#layout-of-a-jolt-app)
16. [When something goes wrong](#when-something-goes-wrong)
17. [Build Jolt itself and run the examples](#build-jolt-itself-and-run-the-examples)
18. [How it works](#how-it-works)

## What works today

- Windows (WebView2) and Linux (WebKitGTK 4.1, X11 or Wayland chosen by GTK at runtime). Several windows at once.
- `loadUrl`, `loadHtml`, `eval` (returns the JSON result) and `addInitScript`.
- `loadResources`: your page as separate HTML, CSS and JS files (or a bundler's output) served from inside the web view with no
  socket, with a stable origin so `localStorage` survives restarts. See
  [Multi-file pages, request handlers and uploads](#multi-file-pages-request-handlers-and-uploads).
- Java request handlers for the page's own requests (`fetch`, forms, htmx, JSON APIs), a `Router` with path parameters, groups,
  filters and error handling, `multipart/form-data` uploads with a configurable size limit, and redirects. Works with any front
  end; htmx is optional.
- String messages in both directions (`window.jolt.postMessage` and `onMessage` in the page).
- Window icon (PNG, or a multi-size `.ico` for crisp taskbar icons), light or dark native title bar, exact title bar
  colours (Windows 11) and the web view's background colour, set at creation or later.
- Every `WebWindow` and `Application` method is safe to call from any thread.
- Developer tools (right-click, Inspect) for debugging the page.
- Packaging into a double-clickable app that bundles its own Java runtime.
- `jolt-credentials` (since 0.2.1, optional): keep passwords and tokens in the Windows Credential Manager or the Linux Secret
  Service. See [Saving passwords and tokens](#saving-passwords-and-tokens-jolt-credentials).
- Start maximized or centered, hide the window until the page is ready, and maximize or restore it later (since 0.2.2). See
  [Opening maximized, centered, and without a flash](#opening-maximized-centered-and-without-a-flash).

See the [changelog](CHANGELOG.md) for what each version added.

## Requirements

| | |
| --- | --- |
| JDK 25 | Jolt uses the Foreign Function & Memory API (final since Java 22). |
| Maven 3.9+ or Gradle | Any build tool that can use Maven Central. See [Add Jolt to your project](#add-jolt-to-your-project). |
| Windows 10/11 | With the [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) (preinstalled on Windows 11). |
| Linux | GTK 3 and WebKitGTK 4.1: `sudo apt install libgtk-3-0 libwebkit2gtk-4.1-0` on Debian/Ubuntu 22.04+ (not the older 4.0 API). |

Run Java with `--enable-native-access=ALL-UNNAMED`, or put `Enable-Native-Access: ALL-UNNAMED` in your jar manifest.
Without it Java prints a warning on every start.

`loadResources` serves your files from inside the web view on WebKitGTK 2.40 or newer; an older WebKitGTK falls back to a
guarded loopback server automatically. See [Requirements and the loopback fallback](#requirements-and-the-loopback-fallback).

## Your first window

```java
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

public final class Main {
    public static void main(String[] args) {
        Application app = Application.create(AppOptions.of("com.example.hello"));
        try {
            app.run(() -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withTitle("Hello").withSize(600, 400));
                window.onMessage(text -> window.postMessage("Java received: " + text));
                window.loadHtml("""
                        <h1>Hello</h1>
                        <button onclick="jolt.postMessage('ping')">Ping Java</button>
                        <p id="out"></p>
                        <script>jolt.onMessage(m => document.getElementById('out').textContent = m)</script>
                        """);
            });
        } finally {
            app.close();
        }
    }
}
```

Click the button: the page sends `ping` to Java, Java answers, and the page shows the answer. That round trip is the whole
mechanism. Everything else in this README is structure around it.

How it fits together:

- **`AppOptions.of("com.example.hello")`**: an identifier for your app. Jolt uses it to choose a per-user data folder
  (`%LOCALAPPDATA%\com.example.hello` on Windows, `~/.local/share/com.example.hello` on Linux) where the web view keeps
  cookies and storage. Letters, digits, `.`, `_` and `-` only.
- **`app.run(onStart)`**: starts the window system and **blocks until the app ends** (last window closed, or `quit()`).
  Create your windows inside `onStart`.
- **`WebWindow`**: one window with a web view. You can create several.
- **`window.jolt`**: injected into every page automatically. `jolt.postMessage(text)` sends a string to Java;
  `jolt.onMessage(callback)` receives strings from Java.

## Talking between the page and Java

### The raw channel

Messages are plain strings, in both directions:

| | Java | Page |
| --- | --- | --- |
| send | `window.postMessage(String)` | `jolt.postMessage(string)` |
| receive | `window.onMessage(listener)` | `jolt.onMessage(callback)` |
| run a script, get the result | `window.eval(String)` returns a `CompletableFuture<String>` of the JSON result | |

Strings of any content work (quotes, newlines, emoji, megabytes). Messages arrive in the order they were sent.

`window.addInitScript(String)` runs a script in every page before the page's own scripts. Use it to hand the page values it
needs on its very first frame, for example `window.addInitScript("window.__SETTINGS__ = {...}")`.

### Requests and responses (the pattern real apps use)

A real app wants to call a Java function from the page and await the answer. Build that on the string channel with a tiny
protocol: the page sends `{"id": 1, "method": "notes.add", "params": {...}}` and Java replies `{"id": 1, "result": ...}` or
`{"id": 1, "error": "..."}`.

The notes example contains both halves, about 40 lines each:

- **Java** (`Rpc.java`): register handlers by name and the class does the rest.

  ```java
  new Rpc(window)
      .on("notes.list",   params -> store.list())
      .on("notes.add",    params -> store.add(params.get("text").getAsString()))
      .on("notes.delete", params -> Map.of("deleted", store.delete(params.get("id").getAsLong())));
  ```

- **Page** (`ui/index.html`): turn it into promises.

  ```js
  const note = await call('notes.add', { text: 'Buy milk' });
  const all  = await call('notes.list');
  ```

If a handler throws, the page's `call(...)` rejects with the error message, so you handle failures with normal
`try/catch`. Generated, typed bindings are not part of Jolt, so copy this small pattern into your project. `Rpc.java` works
with any JSON library; the example uses Gson, which is the example's own choice.

### Threads, and the one rule

Message listeners run on the **UI thread**. Anything slow you do there freezes the window. That is why `Rpc.java` hands
every request to a worker (a virtual thread) and replies from there. `postMessage`, `eval` and every other window method are
safe to call from any thread. The rule:

> Never block the UI thread waiting for something that needs the UI thread. In particular, do not call `.get()` or
> `.join()` on the result of `eval` from a message listener.

## The frontend

Your page is normal HTML, CSS and JavaScript. Use plain files or any framework. The web view is a current Chromium
(Windows) or WebKit (Linux), so write for both.

### While developing: use your dev server

Run your frontend's dev server (Vite, webpack, anything) and point the window at it:

```java
window.setSecurity(window.security().withTrustedOrigins("http://localhost:5173")); // see Security
window.loadUrl("http://localhost:5173");
```

The notes example does this when started with `-Dui.url=...`. Hot reload is your dev server's job and the Java side does
not change. With a real Vite dev server, editing a React component updates the open Jolt window in place, without
reloading the page. Without a dev server, `window.loadResources(AppResources.directory(Path.of("src/main/resources/ui")))`
serves the folder straight from disk, reading each file again on every request, so reloading the page picks up your edits.

### For release: bundle the page into the app

Put the finished page in `src/main/resources/ui` and serve it from the jar. With separate HTML, CSS and JS files (plain files
or the folder a bundler such as Vite produces), use `loadResources`:

```java
window.loadResources(AppResources.classpath("ui"));   // ui/index.html, ui/css/..., ui/js/...
```

The files stay separate, relative URLs and ES modules work, and the page keeps a stable origin so `localStorage` survives
restarts. See [Multi-file pages, request handlers and uploads](#multi-file-pages-request-handlers-and-uploads), which also covers
answering the page's requests from Java.

If you prefer one self-contained HTML file (scripts and styles inlined, which Vite produces with `vite-plugin-singlefile` and the
React example uses), `loadHtml` still works:

```java
String html = new String(MyApp.class.getResourceAsStream("/ui/index.html").readAllBytes(), StandardCharsets.UTF_8);
window.loadHtml(html);
```

Large pages are fine on both platforms (tested with a 3 MB document). On Windows, Jolt serves every `loadHtml` page from memory
under its own address, `https://jolt.localhost`, so there is no size limit and nothing for you to do.

### Using React, TypeScript and Vite

`examples/notes-app/frontend/` is the same notes app with a modern interface: **React + TypeScript**, built with **Vite**,
styled with **Tailwind CSS**, with **Lucide** icons and **Motion** animations. It talks to the unchanged Java backend; the
Java side does not know or care which frontend it is showing. The plain-HTML page in `src/main/resources/ui` is the same
app again in hand-written CSS with no framework, so you can compare the two. Both have a light, a dark and a "follow the
system" theme, and both drive the native title bar to match.

Run these from `examples/notes-app/frontend` (build the Java app first, see [Run it](#run-it)). PowerShell splits `-D`
flags at the first dot, so keep the quotes around them:

```powershell
npm install

# development, with hot reload
npm run dev                                   # Vite on http://127.0.0.1:5173
java --enable-native-access=ALL-UNNAMED "-Dui.url=http://127.0.0.1:5173" `
     -cp "..\target\package\*" io.github.b077as.jolt.examples.notes.NotesApp

# a build to run or ship
npm run build                                 # typechecks, then writes dist\index.html (one file)
java --enable-native-access=ALL-UNNAMED "-Dui.file=dist\index.html" `
     -cp "..\target\package\*" io.github.b077as.jolt.examples.notes.NotesApp
```

To ship it, copy `dist\index.html` over `src\main\resources\ui\index.html` and rebuild the Java app. The page then lives
inside the jar and no flags are needed.

What the example contains, and what to copy into your own project:

| File | Purpose |
| --- | --- |
| `src/jolt.ts` | The **typed bridge**: declares `window.jolt` for TypeScript and provides `call<T>(method, params)`, which returns a promise. This is the only file that knows about Jolt. |
| `src/api.ts` | One typed function per Java handler (`api.list()`, `api.add(text)` and so on). Rename a handler in Java and TypeScript tells you where to update. |
| `src/theme.ts` | The light, dark or system theme: remembers the choice through Java and tells Java the colours so the native title bar matches. |
| `src/useNotes.ts` | The notes and the last error, kept in sync with Java. |
| `src/App.tsx`, `src/components/` | Ordinary React components. They call `api.*`; nothing in them is Jolt-specific. |
| `src/styles.css` | Tailwind setup and the two colour themes as variables. |
| `src/smoke.ts` | A self-test the Java side can trigger (`--smoke`), so the real page is checked against the real backend. |
| `vite.config.ts` | Adds Tailwind and `vite-plugin-singlefile`, which inlines scripts and styles into `index.html`. |

The example was built with React 19, TypeScript 7, Vite 8 and Tailwind 4. The built page passes its self-test against the
Java backend on Windows and on Linux under X11 and Wayland. Vue, Svelte, Solid and plain TypeScript go through the same
Vite setup and produce the same kind of page, but only React has been run.

`src/api.ts` is written by hand and must match the handlers in `NotesApp.java`. If the page is opened in an ordinary browser
(outside Jolt), `window.jolt` does not exist; `call()` then rejects with a clear message instead of crashing.

## Using what Java is good at

Because your backend is just Java, use any library. The example's store (`NotesStore.java`) reads and writes a JSON file in
the app's data folder, atomically:

```java
Path dataDir = AppOptions.of(APP_ID).dataDirectory();       // per-user, per-app folder
NotesStore store = new NotesStore(dataDir.resolve("notes.json"));
```

The same applies to databases (SQLite, H2), HTTP clients, native libraries and background jobs: they live in Java, and the
page asks for them through your handlers.

## Run it

During development, just run your `main`. For the notes example, from the root of this repository:

```powershell
mvn -pl examples/notes-app -am install -DskipTests    # builds it and copies the app and all its jars to examples/notes-app/target/package
java --enable-native-access=ALL-UNNAMED -cp "examples/notes-app/target/package/*" io.github.b077as.jolt.examples.notes.NotesApp
```

Add `--smoke` to run its automatic self-check instead (it exercises every request and exits 0 on success).

Run it from the repository root, because the class path is relative. From another folder, use the full path to
`examples/notes-app/target/package`.

**Developer tools:** create the window with `WindowOptions.defaults().withDevTools(true)`, then right-click the page and
choose Inspect, or call `window.openDevTools()`. Leave them off in released builds.

## Window icon, title bar and colours

These are drawn by the operating system, not your page, so the page cannot style them with CSS. Jolt lets you set them from
Java:

```java
WindowOptions options = WindowOptions.defaults()
        .withIcon(iconBytes)                     // an .ico file with several sizes (best), or PNG data (256x256+)
        .withTitleBarScheme(ColorScheme.DARK)    // the OS draws the title bar dark (or LIGHT)
        .withBackgroundColor(0x0F1117);          // shown behind the page until it paints: no white flash on dark pages
WebWindow window = app.createWindow(options);

// and later, from any thread:
window.setIcon(otherPng);
window.setTitleBarScheme(ColorScheme.LIGHT);
window.setTitleBarColors(0xF4F5FA, 0x1A1D29);    // exact title bar background and text colour (Windows 11 only)
window.setBackgroundColor(0xF4F5FA);
```

Setting them in `WindowOptions` means the window is created that way, so it never appears in the wrong colours first.

| | Windows 11 | Windows 10 | Linux, X11 | Linux, Wayland |
| --- | --- | --- | --- | --- |
| icon | yes | yes | yes | stored, but **not shown**: a Wayland compositor takes the icon from the app's desktop entry |
| title bar light or dark | yes | yes (version 2004 or later) | follows GTK's dark preference, when the window manager draws the title bar | same, depends on the compositor |
| exact title bar colours | yes | no (ignored) | no (ignored) | no (ignored) |
| background behind the page | yes | yes | yes | yes |

On Windows 11 the window was checked by eye (icon, dark and light title bar, and exact colours that blend into the page)
and by tests that read back what Windows holds. On Linux, tests read back what GTK holds under X11 and Wayland. Windows 10
and the look of a real Linux desktop's title bar are not tested. The Linux dark preference is process-wide.

### Opening maximized, centered, and without a flash

Available since **0.2.2**. New windows are centered by default from that version; earlier versions let the operating
system place them.

```java
WindowOptions options = WindowOptions.defaults()
        .withSize(1200, 780)
        .withBackgroundColor(0x0F1117)   // the page's background: painted behind the web view from the first frame
        .withMaximized(true)             // start maximized (false by default)
        .withCentered(true)              // the default: start in the middle of the screen (ignored when maximized)
        .withVisible(false);             // do not show the window until you say so

WebWindow window = app.createWindow(options);
window.onMessage(m -> { if (m.equals("ready")) window.show(); });   // the page sends "ready" once its content is on screen
window.loadResources(AppResources.classpath("ui"));
```

The web view needs a second or two to start. A window that is visible during that time shows an empty body, first the
window's own background and then whatever the web view paints before the page does. Creating the window hidden and calling
`show()` once the page is ready (the page tells you with `window.jolt.postMessage`) removes that: the window appears with
its content already drawn. `show()` also works for a window that was created hidden and shown much later; the page
starts rendering as soon as it appears.

`window.maximize()`, `window.restore()` and `window.isMaximized()` do the same later, from any thread. Maximizing a hidden
window does not show it: the window is maximized when it is first shown.

| | Windows | Linux, X11 | Linux, Wayland |
| --- | --- | --- | --- |
| `withCentered` | middle of the monitor's usable area (without the taskbar); a window bigger than that keeps its top-left corner on screen | middle of the screen | **ignored**: the compositor places windows, an application cannot |
| `withMaximized`, `maximize()`, `restore()` | yes | needs a window manager (a bare X server has none, so nothing happens) | yes, if the compositor allows it |
| window painted with the background colour before the web view starts | yes | the web view's colour is set as before | same |

Checked by tests that read back where Windows put the window and what it painted, by a Windows run of a real app (it
opened centered or maximized, and with a hidden start no white or black frame was seen), and by the shared contract tests
under X11 and Wayland in Docker. Centering was read back from GTK under X11 only. Not tested: several monitors,
Windows 10, and a real Linux desktop's window manager.

### Letting the page drive it

The page decides when the theme changes (a switch the user clicks, or the system theme changing) but cannot restyle the
window itself. It asks Java through a handler, exactly like any other request. The notes example does this:

```js
// page: after the user picks a theme
await call('window.setTheme', { preference: 'dark', scheme: 'dark', background: '#0f1117', text: '#e7e9ee' });
```

```java
// Java: validate, apply, and remember
.on("window.setTheme", params -> {
    ThemeRequest theme = ThemeRequest.parse(params);          // rejects anything that is not exactly what we expect
    window.setTitleBarScheme(theme.scheme());
    window.setTitleBarColors(theme.background(), theme.text());
    window.setBackgroundColor(theme.background());
    settings.save(...);                                        // so the next start is already in the right colours
    return Map.of("ok", true);
})
```

Validate what the page sends (`ThemeRequest` checks the values are exactly `light` or `dark` and `#rrggbb`); the page is
untrusted input. The example also remembers the choice in a small settings file, creates the next window with it, and passes
it to the page before any script runs (`window.addInitScript("window.__JOLT_PREFERENCE__ = ...")`), so the very first frame
is already correct, with no flash of the wrong theme.

### A fully custom title bar

A frameless window whose top strip is your own HTML, with your own minimise, maximise and close buttons, is not available
yet. For now, `setTitleBarColors` on Windows 11, or the light and dark schemes elsewhere, give a native title bar that
matches your page.

## Change the app icon

The icon is the picture in the title bar, the taskbar and Alt+Tab. In the notes example it is
`examples/notes-app/src/main/resources/icon.ico`, loaded at start-up with:

```java
WindowOptions.defaults().withIcon(readBundledBytes("/icon.ico"))
```

### Why an `.ico`, not one big PNG

Windows asks for the icon at several small sizes: 16 px in the title bar, and 32 px or more in the taskbar and Alt+Tab,
depending on screen scaling. If you give it a single 256 px PNG it has to shrink it itself, and the result is jagged and
grainy, especially for thin strokes. An `.ico` file holds the same icon pre-drawn at several sizes. Jolt hands Windows the
image whose size matches, so nothing is scaled and every size is smooth. On Linux the largest PNG inside the `.ico` is used,
so one file serves both systems. A plain PNG still works, with the grainy-at-small-sizes caveat.

### Swap in your own icon

1. **Make an `.ico`** that contains at least 16, 24, 32, 48 and 256 px images. Any icon editor or converter that exports a
   multi-size `.ico` works. Better still, export each size from your vector artwork instead of shrinking one bitmap, so
   small sizes stay sharp. Classic bitmap-style entries are fine too (Jolt converts them internally).
2. **Replace the file.** Put it in your project's `src/main/resources` (the example uses `icon.ico`), overwriting the old
   one, or point `readBundledBytes` at a new name.
3. **Update the logo in your page**, if it shows one, so the page header matches the window icon. The example pages use an
   inline SVG: `src/components/Logo.tsx` in the React project and the `<svg class="logo">` block in `ui/index.html`.
4. **Rebuild** so the new files reach the app:

   ```powershell
   cd examples\notes-app\frontend
   npm run build                                               # React page: rewrites dist\index.html
   copy dist\index.html ..\src\main\resources\ui\index.html    # only if you ship the React interface
   cd ..\..\..
   mvn -pl examples/notes-app -am install -DskipTests
   ```

5. **Run it again.** Windows caches taskbar icons, so if the old one still shows, unpin and re-pin the app or restart
   Explorer.

To change the icon at run time instead, call `window.setIcon(bytes)` from any thread.

### Keep it readable when small

- Use one bold shape. Avoid hairlines and detail finer than about 1/8 of the icon.
- Keep the gaps (the inside of a hook, the space under a bar) at least as wide as the lines are thick, or they fill in.
- Look at it at 16, 24 and 32 px, on a light and a dark background, before you ship it.

### If the logo in the page shows a thin outline

A page that styles every `svg` with a stroke (common for line-icon sets) gives an inline SVG logo a stroke too, which draws
as a thin border around its tile. Give the logo `stroke: none`. If it has a soft shadow, also give it `overflow: visible`,
because an SVG clips anything outside its own box and cuts the shadow off in a hard edge.

## Package it

`jpackage` (part of the JDK) turns your jars into a double-clickable app that **bundles its own Java runtime**, so users
install nothing but the system web view.

```powershell
mvn -pl examples/notes-app -am install
jpackage --type app-image --name JoltNotes `
  --input examples\notes-app\target\package `
  --main-jar notes-app-0.1.0-SNAPSHOT.jar --main-class io.github.b077as.jolt.examples.notes.NotesApp `
  --java-options "--enable-native-access=ALL-UNNAMED" `
  --add-modules "java.base,java.logging,jdk.unsupported" `
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress zip-6" `
  --dest dist
# result: dist\JoltNotes\JoltNotes.exe  (about 32 MB; without --add-modules and --jlink-options about 120 MB)
```

The resulting `JoltNotes.exe` passes the example's self-test on Windows. Add whatever modules *your* dependencies need to
`--add-modules`. If the app fails at start with a missing class, that is the first thing to check.

Good to know:

- The `app-image` above is a folder you can zip and ship. Installers (`--type msi` or `exe`) need the WiX tools, and
  `--type deb` or `rpm` need their packaging tools.
- The same `jpackage` command should work on Linux, but it has not been run there.
- Code signing is up to you.
- Users on Windows 10 need the WebView2 Runtime (a free download from Microsoft). Users on Linux need the packages listed
  under [Requirements](#requirements).

## Things to know

- **Let `main` return instead of calling `System.exit`.** On Windows a forced exit makes Microsoft's web view code print
  `Failed to unregister class Chrome_WidgetWin_0. Error = 1411`. It is harmless, but it never appears if `main` just ends.
- **One app at a time per process.** `run` blocks until the app ends. On Linux the window system runs on a thread Jolt
  owns (the web libraries require one fixed thread), so do not assume the thread calling `run` is the UI thread. Use
  `app.isUiThread()` or `app.dispatch(Runnable)` when you need to be sure. Tasks given to `dispatch` after the loop has
  ended are discarded.
- **`eval` fails when the script throws or does not compile**, on both platforms, with a `JoltException` that carries the
  script's error message. If the window closes first, it fails with `WindowClosedException`.
- **Listeners and futures run on the UI thread.** Do not block it waiting for a future the UI thread has to complete.
- **Close handling:** `window.onClosed(...)` runs once after a window is destroyed. By default the app ends when the last
  window closes (`AppOptions.withQuitWhenLastWindowClosed(false)` changes that).
- **Wayland limits** are Wayland's, not Jolt's: an app cannot position its own window or keep it on top.
- **Security: the page can call everything you register.** Jolt only lets your own content and origins you list reach your
  handlers by default; see [Security](#security). Treat handler parameters as untrusted input anyway: validate them, as
  `NotesStore.add` does for an empty note.

## Multi-file pages, request handlers and uploads

`loadHtml` takes one string, so relative `<link>`, `<script src>` and ES modules have nothing to resolve against. To keep
HTML, CSS and JS in separate files, serve them with `loadResources`:

```java
window.loadResources(AppResources.classpath("ui"));          // src/main/resources/ui/index.html, css/, js/ ...
window.loadResources(AppResources.directory(Path.of("ui"))); // a folder on disk, handy while developing
```

The page can then use relative URLs, `type="module"`, `fetch`, fonts and any front end (plain files, htmx, React, Vue, ...). The
files are served **inside the web view**, with no socket: WebView2 and WebKitGTK both let an application answer requests for an
address of its own, so only this window's web view can reach them. The page's origin is `https://jolt.localhost` on Windows
(the same as `loadHtml` pages) and `jolt://app` on Linux. It never changes, so `localStorage`, IndexedDB and cookies survive a
restart. Don't hard-code the origin in your page; use relative URLs.

### Answering requests from Java

Pass a `RequestHandler` and the page's own requests (`fetch`, `XMLHttpRequest`, `<form method="post">`, htmx's `hx-get` and
`hx-post`) are answered by Java before the files are. It does not care what the front end is: return HTML fragments for htmx,
JSON for a single-page app, or redirects for classic forms.

```java
window.loadResources(AppResources.classpath("ui"), request -> switch (request.path()) {
    case "/notes" -> AppResponse.html(renderNotes());                       // GET /notes
    case "/api/notes" -> AppResponse.text(notesAsJson())                    // GET /api/notes
            .withHeader("Content-Type", "application/json");
    case "/notes/add" -> {                                                  // POST, from hx-post or a form
        notes.add(request.formValue("text"));
        yield AppResponse.html(renderNotes()).withHeader("HX-Trigger", "noteAdded");
    }
    default -> null;                                                        // null: let the files answer
});
```

`AppRequest` has the method, path, query, headers and body, with `formValue`, `queryParams` and `isHtmx()` helpers.
`AppResponse` has `html`, `text`, `status`, `redirect`, `withHeader` and `withStatus`. Handlers run on worker threads (use
`Application.dispatch` to touch windows). Escape anything user-supplied before putting it in HTML, and keep `GET` handlers free of
side effects. Bodies over 8 MB (configurable, see [Uploads](#uploads-multipartform-data)) get 413; an exception in a handler gives a
bare 500 and the details go to the log, not the page.

Redirects work everywhere: a form post answered with `AppResponse.redirect(...)` (Post/Redirect/Get) ends on the new address.
WebKitGTK does not follow redirects from an in-process scheme itself, so on Linux Jolt does it: a page navigation gets a tiny page
that moves to the new address, and `fetch`/`XMLHttpRequest` get the target's answer directly (after a 301, 302 or 303 as a `GET`).
Only same-origin redirects are followed that way.

### Routing

A lambda with a `switch` is fine for a few routes. For more, use `Router`, which is a `RequestHandler`:

```java
Router router = Router.create()
    .get("/", request -> AppResponse.html(pages.home()))
    .get("/notes/{id}", request -> AppResponse.html(pages.note(request.param("id"))))
    .post("/notes", request -> {
        notes.add(request.formValue("text"));
        return AppResponse.redirect("/notes");
    })
    .group("/admin", admin -> admin
        .before(request -> loggedIn(request) ? null : AppResponse.redirect("/login"))   // null: carry on
        .get("/users", request -> AppResponse.html(pages.users())))
    .onError((request, error) -> AppResponse.html(pages.error(error)).withStatus(500));

window.loadResources(AppResources.classpath("ui"), router);
```

- **Patterns:** `/notes/{id}` captures one segment (read it with `request.param("id")`, already URL-decoded); `/files/{*path}`
  as the last segment captures the rest. Trailing slashes make no difference; plain segments are case-sensitive.
- **Which route wins:** a plain segment beats `{id}`, which beats `{*rest}`, decided from the left; otherwise the route registered
  first. Registering the same method and shape twice (even with different parameter names) throws.
- **Fall-through:** a request no route matches goes to your files, so static assets need no routes. `HEAD` uses the `GET` route.
  A `POST`, `PUT`, `PATCH` or `DELETE` to a path that only has other methods gets 405 with an `Allow` header; a `GET` there falls
  through, so a `POST`-only route never hides a file.
- **Filters** (`before`) run in order, outer groups first, only for requests that matched a route. Return a response to stop (a
  login redirect, a 403) or `null` to continue.
- **Errors:** `onError` turns an exception from a filter or route into a response. Without it, or if it returns `null`, Jolt
  answers 500 and logs the cause.

### Uploads (multipart/form-data)

A form with a file input, or `fetch` with a `FormData`, sends `multipart/form-data`. `AppRequest.parts()` reads it:

```java
.post("/documents", request -> {
    String title = request.formValue("title");                 // the text fields work as usual
    AppRequest.Part file = request.part("document");           // a file input
    if (file == null || file.filename().isEmpty()) {           // an empty name: no file was chosen
        return AppResponse.html("<p>Choose a file</p>").withStatus(422);
    }
    Files.write(uploads.resolve(UUID.randomUUID() + ".bin"), file.content());
    log.info("stored {} ({} bytes, {})", file.filename(), file.size(), file.contentType());
    return AppResponse.redirect("/documents");
})
```

```html
<form method="post" action="/documents" enctype="multipart/form-data">
  <input name="title"> <input type="file" name="document"> <button>Upload</button>
</form>
```

It works with plain forms, `fetch`, and htmx (`hx-post` with `hx-encoding="multipart/form-data"`), on both platforms and both
transports. A `Part` has `name()`, `filename()` (`null` for an ordinary field), `contentType()`, `headers()`, `content()`,
`text()` and `size()`. `parts()` throws `IllegalArgumentException` for a body that is not well formed, which `Router.onError` can
turn into a 400.

- **Treat the file as hostile.** `filename()` is cut down to its last path segment with control characters removed (so
  `../../etc/passwd` becomes `passwd`), but it is still what the user's machine said; store files under a name you choose, as above.
  `contentType()` is what the browser guessed from the extension, not a check of the contents.
- **Size:** bodies are held in memory, 8 MB by default; a bigger one is answered with 413 and never reaches your handler. Raise it for
  uploads with `window.loadResources(resources, handler, ResourceOptions.defaults().withMaxRequestBytes(64 * 1024 * 1024))`
  (up to 1 GB, held in memory, so size it to what you need). Streaming a large file to disk is not supported.
- **Linux:** WebKitGTK aborts the whole process when a request to an in-process scheme carries a body holding a `Blob` (any
  `fetch` with a `Blob`, any `FormData` with a file in it, a multipart form with a file input), so Jolt's Linux backend turns
  those bodies into plain bytes inside the page before they are sent. You don't have to do anything: `fetch`, `XMLHttpRequest`
  (so htmx too), `Request` objects, `navigator.sendBeacon` and `<form>` submissions (`requestSubmit()` and `form.submit()` as well)
  all work with files, and what your handler receives is identical on Windows and Linux. Two corners are outside its reach: a
  *synchronous* `XMLHttpRequest` with a `Blob` throws a `NotSupportedError` (rather than crashing), and code in a Web Worker has to
  send bytes (`ArrayBuffer`), not a `Blob`. A form holding a file is sent as a `fetch` and its answer replaces the page, which
  looks like a normal navigation (a redirect included) except that the address does not change to the form's `action`.
- Over the loopback fallback, Jolt adds its secret to multipart forms as a hidden field and removes it before your handler sees the
  parts. `navigator.sendBeacon` cannot carry the secret there and is refused.

### Requirements and the loopback fallback

The in-process transport needs WebView2 on Windows (always the case) and WebKitGTK 2.40 or newer on Linux (with libsoup 3, which
every WebKitGTK 4.1 install has). A system that lacks them falls back automatically to a server on a loopback port; you can
also ask for that, for example to open the same address in a normal browser to debug:

```java
window.loadResources(resources, handler, 48123);   // http://127.0.0.1:48123, fixed port so storage survives restarts
```

To combine a fixed port with a bigger upload limit, use
`window.loadResourcesOverLoopback(resources, handler, ResourceOptions.defaults().withMaxRequestBytes(...), 48123)`.

Any program on the machine, and any website the user has open in a browser, can send requests to a loopback port, so Jolt guards it:

- **CSRF:** `POST`/`PUT`/`PATCH`/`DELETE` are refused unless they carry a random per-run secret in `X-Jolt-Token`. Jolt adds it
  to htmx requests and to `fetch` calls from your page, and only for URLs on its own server, so it never reaches another
  origin. Plain `<form method="post">` forms work too: Jolt adds the secret as a hidden `_jolt_token` field when the form is
  submitted (for forms posting to its own server, urlencoded or multipart) and removes it before your handler sees the body. It
  is also available as `window.joltCsrfToken` for `XMLHttpRequest`. `form.submit()` is covered too. Other body types such as
  `text/plain`, and `navigator.sendBeacon`, cannot carry the secret and are refused. The handler never sees the secret.
- `Origin` must match if present, `Sec-Fetch-Site` must be `same-origin` or `none` (this also rejects a page served from another
  port on the same machine), and `Host` must name the server (DNS rebinding).
- No CORS headers are sent and `OPTIONS` is refused, so another origin cannot read a response.

None of this is needed in-process, where nothing but the window's own web view can send a request; a state-changing request there
is still refused if its `Origin` is not the app's.

## Security

The page can call everything you register with `onMessage`, so Jolt decides which pages may reach it. Each window has a
`SecurityPolicy`. The default, `SecurityPolicy.standard()`, trusts only your own content (what you pass to `loadHtml` or serve
with `loadResources`) plus origins you list, and blocks the rest:

| Protection | What it does when on | Switch |
| --- | --- | --- |
| Navigation lock | Links, redirects, frames and `loadUrl` to an untrusted origin are cancelled | `withLockNavigation` |
| Message check | Messages from an untrusted page never reach `onMessage` | `withRestrictMessages` |
| New windows | `window.open` and `target=_blank` are refused (Windows; WebKitGTK never opens one itself) | `withBlockNewWindows` |
| Permissions | Camera, microphone, location and similar requests are denied | `withDenyPermissions` |
| Bridge in frames | `window.jolt` exists only in the top-level page, not in iframes (set at window creation) | `withBridgeInSubframes` |

An *origin* is scheme, host and port, such as `http://localhost:5173`. Change the policy when you create the window, or later
while it is open:

```java
// Trust your dev server (Vite) or your own site, in addition to your bundled page
WindowOptions options = WindowOptions.defaults()
        .withSecurity(SecurityPolicy.standard().withTrustedOrigins("http://localhost:5173"));

// Or change it at any time; it applies to everything that happens from then on
window.setSecurity(window.security().withTrustedOrigins("https://app.example.com"));
window.setSecurity(window.security().withLockNavigation(false));   // let the window go anywhere

// Everything off: any page can navigate, open windows, ask for permissions and call your handlers
WindowOptions open = WindowOptions.defaults().withSecurity(SecurityPolicy.permissive());
```

When something is stopped, Jolt logs a warning and calls your `onBlocked` listener (on the UI thread, after the blocked
action), for example to open a blocked link in the user's own browser:

```java
window.onBlocked(event -> {
    if (event.kind() == BlockedEvent.Kind.NAVIGATION) {
        openInSystemBrowser(event.url());
    }
});
```

Things to know:

- **`loadUrl` to another site is blocked by default.** Add the origin with `withTrustedOrigins`, as the notes example does for
  its dev server.
- **Trusting an origin gives that site your handlers.** Whatever you register with `onMessage` can be called by every page from
  a trusted origin, so list only sites you control, and keep treating handler parameters as untrusted input.
- **Windows and Linux differ in one respect.** WebView2 reports which page sent a message, so the check is exact. WebKitGTK does
  not say which frame sent it, so on Linux the check uses the top-level page, and the navigation lock is what keeps untrusted
  frames out of a trusted page. If you turn the navigation lock off on Linux, also leave the bridge out of frames
  (the default).
- **A blocked navigation is cancelled before the page loads, but the request may still reach that server.** Nothing from it is
  shown or run.
- WebView2 ignores a navigation to the exact address it has just cancelled; if you react to a block by trusting the origin,
  load a different page of the site, or load it before the first attempt.
- In the page, set text with `textContent`, never `innerHTML`, when it comes from users or files.

## Saving passwords and tokens (`jolt-credentials`)

A desktop app that logs in to something (a mail server, an API) has a password or token to keep. Do not write it to a file
of your own: put it in the operating system's keystore. `jolt-credentials` does that through the same FFM approach as the
rest of Jolt, with no native code to build. It does not depend on `jolt-core`, so it also works in a program with no window.
Available since **0.2.1**; versions before that do not have it.

```xml
<dependency>
  <groupId>io.github.b077as</groupId>
  <artifactId>jolt-credentials</artifactId>
  <version>${jolt.version}</version>
</dependency>
```

Gradle: `implementation("io.github.b077as:jolt-credentials:0.2.2")`.

```java
CredentialStore store = CredentialStore.open("com.example.mail");   // your app's own namespace
store.put("alice@example.com", password);                           // replaces an earlier one
Optional<String> saved = store.get("alice@example.com");
store.delete("alice@example.com");                                  // true if there was one
```

| | Where the secret goes | Needs |
| --- | --- | --- |
| Windows | Credential Manager (`CredWriteW`, `CredReadW`, `CredDeleteW`) | nothing. A secret is at most 2560 bytes in UTF-8. |
| Linux | the Secret Service through `libsecret` (GNOME Keyring, KWallet, KeePassXC) | `libsecret-1.so.0` (Debian and Ubuntu: `libsecret-1-0`) and a running, unlocked Secret Service |

- **Check for it when you start.** `CredentialStore.open` throws `CredentialStoreUnavailableException` on a machine with no
  keystore (macOS, no `libsecret`, no Secret Service running, as on a minimal window manager or in a container). The message
  says why. Catch it and fall back to something else, such as asking the user each time.
- **Secrets are text** (UTF-8, not empty, no NUL characters). An account is any non-blank name up to 255 characters.
- **Call it off the UI thread.** Every call blocks, and on Linux it may wait for the user to unlock the keyring.
- **What it protects against:** other users of the machine and someone reading the disk. It does not protect against a
  program running as the same user, which can ask the keystore for the same secrets. A Java `String` cannot be wiped from
  memory, so do not hold one longer than you need to.

## Layout of a Jolt app

This is the notes example, which you can copy as a starting point:

```
notes-app/
  pom.xml                                   depends on jolt-core, jolt-win32, jolt-linux (+ your libraries)
  src/main/java/.../NotesApp.java           main(): create the app and window, register handlers, load the page
  src/main/java/.../Rpc.java                the request/response layer (copy it)
  src/main/java/.../NotesStore.java         the app's own logic and data
  src/main/java/.../SettingsStore.java      remembered settings (the theme), read before the window is created
  src/main/java/.../ThemeRequest.java       validates what the page sends before it reaches the operating system
  src/main/resources/ui/index.html          the page (plain HTML and CSS version)
  src/main/resources/icon.ico               the window icon, drawn at nine sizes so Windows never has to scale it
  frontend/                                 the same interface as a React + TypeScript + Tailwind project
  src/test/java/.../*Test.java              your logic is plain Java, so test it with plain JUnit
```

Keep your logic in ordinary classes with no window code in them, as `NotesStore`, `SettingsStore` and `ThemeRequest` do.
They then test without a window, and only `NotesApp` and `Rpc` touch Jolt.

## When something goes wrong

| Symptom | Likely cause |
| --- | --- |
| `No usable Jolt platform backend: ... WebView2 Runtime is not installed` | Install the WebView2 Runtime (Windows). |
| `GTK 3 / WebKitGTK 4.1 could not be loaded` | Install `libgtk-3-0` and `libwebkit2gtk-4.1-0`. The error message names the packages for Debian/Ubuntu, Fedora and Arch. |
| `GTK could not open a display` | No `DISPLAY` or `WAYLAND_DISPLAY`: you are on a server or in a container without a display. |
| Warning about native access when starting | Add `--enable-native-access=ALL-UNNAMED` (or `Enable-Native-Access: ALL-UNNAMED` in your jar manifest). |
| `Could not find or load main class` | The class path is wrong. The examples use a path relative to the repository root, so run them from there. |
| `Could not find or load main class .url=...` (PowerShell) | PowerShell split `-Dui.url=...` at the dot. Quote it: `"-Dui.url=http://127.0.0.1:5173"`. |
| The window opens but stays blank | Open developer tools (see [Run it](#run-it)) and look at the console. With `loadUrl`, check the dev server is running. |
| `call(...)` never resolves | Your handler never replied, or the page was reloaded (pending calls belong to the old page). |
| The window freezes | Something slow ran inside a message listener. Move it to a worker thread. |
| The old icon still shows in the taskbar | Windows caches it. Unpin and re-pin the app, or restart Explorer. |

## Build Jolt itself and run the examples

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'           # any JDK 25
mvn install                                    # builds everything and runs all tests (they open real windows)
```

Run the smallest example, a window with a scripted round trip (`--smoke` runs an automatic self-check):

```powershell
$r = (Get-Location).Path
java --enable-native-access=ALL-UNNAMED `
  -cp "$r\examples\hello-window\target\classes;$r\jolt-core\target\classes;$r\jolt-win32\target\classes;$r\jolt-linux\target\classes" `
  io.github.b077as.jolt.examples.hello.HelloWindow
```

Run the full notes example, in either interface (from the repository root):

```powershell
mvn -pl examples/notes-app -am install -DskipTests

# plain HTML interface
java --enable-native-access=ALL-UNNAMED -cp "examples/notes-app/target/package/*" io.github.b077as.jolt.examples.notes.NotesApp

# React interface (build the frontend first: cd examples\notes-app\frontend; npm install; npm run build)
java --enable-native-access=ALL-UNNAMED "-Dui.file=examples/notes-app/frontend/dist/index.html" -cp "examples/notes-app/target/package/*" io.github.b077as.jolt.examples.notes.NotesApp
```

| Module | What |
| --- | --- |
| `jolt-core` | Public API (`Application`, `WebWindow`), platform SPI, shared FFM helpers, page-side bridge script |
| `jolt-win32` | Windows backend: Win32 window and WebView2, driven through COM vtables from Java |
| `jolt-linux` | Linux backend: GTK 3 window and WebKitGTK 4.1, driven through FFM |
| `examples/hello-window` | Smallest app; `--smoke` runs a scripted round trip |
| `examples/notes-app` | A complete small app (Java data, JSON requests, HTML page); the worked example above |
| `examples/notes-app/frontend` | The same app's interface as a React + TypeScript + Tailwind project, with hot reload and a single-file build |

## How it works

`Application.run` turns the calling thread into the UI thread and runs the platform message loop. Anything from another
thread goes through `dispatch`, which queues the task and wakes the loop. On Windows, WebView2's COM interfaces are called
straight through their vtables with FFM, and the callback interfaces WebView2 wants are implemented in Java as COM objects
whose methods are FFM upcall stubs. The vtable slot numbers are generated from Microsoft's header rather than typed by
hand, because a wrong slot or signature crashes the JVM instead of throwing.

`loadResources` rests on one transport-independent class, which serves the files, calls your handler and turns failures into
HTTP errors. On Windows the web view hands it every request to `https://jolt.localhost` (WebView2's request interception, answered
asynchronously so a slow handler never blocks the UI thread). On Linux it is a URI scheme registered with WebKitGTK. Both read the
request, run the handler on a virtual thread, and write the response back on the UI thread. Where that is not possible, the same
class sits behind a small HTTP server on the loopback interface, with the guards described above. Linux has two extra pieces, both
there because of WebKitGTK: Jolt follows same-origin redirects itself, and a small script in the page turns request bodies that hold
a `Blob` into bytes, since WebKitGTK aborts the process on them.
