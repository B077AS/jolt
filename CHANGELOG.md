# Changelog

All published versions of Jolt, newest first. Every module (`jolt-core`, `jolt-win32`, `jolt-linux`, `jolt-credentials`) is
released with the same version number. Jolt is early software (version 0.x): APIs can still change between versions, and
this file says when they do.

The latest version is the one on [Maven Central](https://central.sonatype.com/artifact/io.github.b077as/jolt-core).

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
