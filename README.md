# Jízdní řády for Nokia 9300 (J2ME)

This is a Java ME client for the Czech public transport timetable service used by the Android app **Jízdní řády** (`cz.fhejl.pubtran`). It targets the **Nokia 9300 / 9500 Communicator**: Series 80 v2, EKA1 kernel, MIDP 2.0 / CLDC 1.1, 640×200 screen.

It is a copy of this project's **discord-j2me** fork, reusing its build toolchain, KEmulator setup, HTTP layer and the look of the JSONPlaceholder test screen. The JSONPlaceholder content has been replaced by the pubtran client.

- **Backend:** `https://pubtran-backend.mapy.cz/api/v1/`. It speaks Seznam **FastRPC** (binary) and needs no login or API key. It was reverse-engineered from the Android app; see `..\PubtranClient\API.md`.
- **TLS:** two transports.
  - **Java TLS** (`JavaTls`, signed builds only): BouncyCastle TLS 1.2 over `socket://`. It sends SNI and skips the certificate chain check, like the native EKA1 patch. The connection is kept open (HTTP/1.1 keep-alive) and reused, so only the first request pays for the slow Java handshake; if the server closed the idle connection, the request is sent again over a new one. A later handshake resumes the previous TLS session when the server allows it.
  - **Native TLS** (`NativeHttp`): `HttpConnection` (`https://`) through the TLS 1.2 patch for `SSLADAPTOR.dll`. On the 9300 it currently can't reach pubtran-backend.mapy.cz from Java (no SNI, the phone stalls; see [symbian-tls#13](https://github.com/shinovon/symbian-tls/issues/13)). Java TLS is compiled only into builds with the `JAVA_TLS` define (the signed and debug targets), which the 9300 can't use, so the phone build uses native TLS and doesn't bundle BouncyCastle (230 KB instead of 603 KB).
- **Kept from the test screen:** the tile start page, the loading overlay with spinner and live transfer progress ("Připojování…", "4.2 KB / 12.0 KB (~1 s)"), and the request log with timing, sizes and TLS details.

## Screens

| Screen | What it does | App call |
|---|---|---|
| **Start** (tiles: Odkud · Kam · Přes · Kdy · Hledat · Log · HTTPS test) | The search form. *Hledat* runs the search with the loading overlay drawn over the tiles. | `getroutesopt` |
| **Odkud / Kam / Přes** | A hand-drawn list: one click or Enter on a place picks it. Type on the keyboard and press Enter to search (nothing is sent while typing: on the 9300 every request is a full TLS handshake, since the EKA1 TLS patch has no session resumption). Clicking the field opens the phone's text editor. With an empty field it shows recently used places, stored in RMS on the phone. | `suggest` |
| **Kdy** | Date and time (or "now"), departure/arrival, direct connections only, low-floor, and transport modes. | – |
| **Výsledky** | The connection list. *<< Dřívější spoje* and *Další spoje >>* are the first and last rows (they replace the app's scroll up/down). Delays come with each page; *Obnovit zpoždění* fetches fresh ones (nothing is requested automatically). | `getroutesopt` index −5/+5 + `hashes_used`, `gettripinfos` |
| **Detail spojení** | Every leg with times, platforms, delays, warnings and ticket prices. *Všechny zastávky* toggles the intermediate stops. *Spoj N: …* opens a ride. | `gettripinfos` |
| **Detail spoje** | The whole trip of one ride; the part you travel is highlighted. *< Předchozí spoj* and *Následující spoj >* replace the app's swipe. | `getnextdepartures` reqindex ±1, ±2… + `gettripinfos` |
| **Log** | Each row shows the method, total time, size and the TLS handshake time (or "spoj. znovu" for a reused connection). Selecting a row shows the full URL, transport, request and response headers, HTTP status, timing (TCP connect, TLS handshake full/resumed, until the response status, download), and the TLS protocol, cipher suite and certificate. | – |
| **HTTPS test** | GET to a chosen server over **native** or **Java** TLS. Shows HTTP status, time, size, protocol, cipher and certificate issuer. Java TLS also shows TCP and handshake time separately and sends a second GET over the same connection. *Otestovat lehké stránky* runs a list of small text pages. | `https://` / `socket://` |

The Nokia 9300 has no touch screen. Move around with the navi key and use the commands on the Communicator's right-hand buttons or in the menu.

## Code

All new code is in its own package, **`src/pubtran/`**, separate from the Discord code in `src/com/gtrxac/discord/`:

| Files | Purpose |
|---|---|
| `Frpc`, `FrpcStruct`, `FrpcDate` | FastRPC 2.1 encoder/decoder (hand-written UTF-8, no `Calendar` time zone maths). |
| `PubtranApi` | The endpoints. Each has a request builder (`*Params`) and a response parser (`parse*`), plus `call()`, which handles progress and logging. |
| `JavaTls`, `JavaTlsClient`, `SniServerName` | Java TLS: BouncyCastle client (SNI, ECDHE/RSA suites, no chain check, session resumption), HTTP/1.1 keep-alive over one shared connection, retry over a new connection when the idle one was closed. |
| `NativeHttp` | `StandardHTTP` over `HttpConnection` (native TLS); records the status, headers and `SecurityInfo` for the log. |
| `TlsTestScreen` | The HTTPS test (native or Java TLS). |
| `src/org/bouncycastle/...` | Replace BouncyCastle's `CustomNamedCurves` / `ECNamedCurveTable` with just P-256 and P-384. The originals keep ~140 unused curve classes in the JAR (836 KB → 603 KB). |
| `Place`, `Route`, `RoutePart`, `TripStop`, `Info`, `SearchState`, `RecentPlaces`, `Fmt` | Data model, search state, recent places (RMS) and Czech formatting. |
| `StartScreen`, `PlaceScreen`, `WhenScreen`, `ResultsScreen`, `RouteScreen`, `TripScreen` | The UI. |
| `TileScreen`, `Spinner`, `LoadingScreen`, `LoadingHost`, `RequestThread`, `RequestCallback`, `RequestLog`, `LogEntry`, `LogScreen`, `LogDetailScreen`, `TlsInfo` | Carried over from the JSONPlaceholder test screen. |

The only changes to the Discord code are these:

- `App` starts `pubtran.StartScreen` and makes `App.disp` public. Set `App.USE_PUBTRAN_START_SCREEN = false` to get the Discord client back.
- The ModernConnector code is removed; BouncyCastle (`lib/bouncycastle.jar`, from discord-j2me) is bundled again for `pubtran.JavaTls`.

The Discord client stays in the JAR. That adds to its size, but keeps this a straight fork, so fixes can still be merged from discord-j2me.

### Verification

The FastRPC codec and request builders were checked on a PC against the traffic captured from the Android app (`..\pubtran.flow`):

- All 34 recorded requests re-encode **byte-for-byte**.
- `suggestParams`, `searchParams` (first page, index 5, index 10 + hashes), `otherRunParams` (reqindex 2) and `tripInfosParams` produce exactly the bytes the app sent.
- Every recorded response parses.

The UI itself has not been run on the phone yet.

## TLS measurements on the Nokia 9300

Measured with the TLS test:

- **jsonplaceholder.typicode.com:** HTTPS 7.3 s vs HTTP 1.4 s, so the TLS handshake costs **~5.9 s** (the EKA1 TLS patch does a full handshake on every connection).
- **www.google.com, www.seznam.cz:** fail after ~3 s with *Unexpected end of stream*.
- **pubtran-backend.mapy.cz:** stalls the phone until the USB internet link drops (Symbian error **-29**). Java TLS would avoid this, but can't run on the 9300 (see Building).
- The causes (no SNI from Java, device-only failures) are reported in [symbian-tls#13](https://github.com/shinovon/symbian-tls/issues/13).

## Java TLS / signing tests on the Nokia 9300

Java TLS needs `socket://`. Results so far (October 2026, TLS patch v19, phone date correct, online certificate check off):

| # | Build | Signed with | Install method | Result |
|---|---|---|---|---|
| 1 | Java TLS, unsigned (`.jar`) | – | `.jar` | Installs; `socket://` → **SecurityException** after 47 ms |
| 2 | Java TLS, `.jad` with wrapped lines | Darkman | `.jad` (sideloaded) | "Podpis není" – the JAD parser doesn't support continuation lines (fixed: one line per attribute) |
| 3 | Java TLS, socket permission required | Darkman | `.jad` | Online cert check hung 2 min (URL `ion.server.url` is a placeholder), then **"odmítnuta serverem jazyka Java"** |
| 4 | same, online cert check off | Darkman | `.jad` | **refused** ("odmítnuta serverem jazyka Java") |
| 5 | socket permission optional | Darkman | `.jad` | **refused** |
| 6 | no permissions at all | Darkman | `.jad` | **refused** |
| 7 | unsigned, via JAD | – | `.jad` | "Untrusted, continue?" → **installs** (JAD route works) |
| 8 | own self-signed certificate (`pubtran-sign.cer` imported, trusted for Java/app install) | own | `.jad`, app removed first | **refused** |
| 9 | root + signer chain (`pubtran-root.cer` imported) | chain | `.jad` | **"Ověření certifikátu se nezdařilo – Digitální podpis nelze ověřit"** (root found, signature check fails) |
| 10 | 2 KB SignTest MIDlet, CRLF JAD | chain | `.jad` | **refused** (not a JAR size problem) |
| 11 | 2 KB SignTest MIDlet, LF JAD | chain | `.jad` | **refused** (not a line-ending problem) |
| 12 | SignTest over the air (`ota/`), CRLF JAD | chain | phone browser (Save and open) | **refused**; the phone downloaded only the JAD, never the JAR |
| 13 | SignTest over the air (`ota/`) | Darkman | phone browser | **refused**; again only the JAD (1.2 kB) was downloaded |

All signatures verify on the PC (`openssl dgst -sha1 -verify`). Over the air, the phone refuses the suite from the JAD alone, before downloading the JAR, so the JAR (size, transfer, signature bytes) is not the cause: the phone does not accept a signer certificate that chains to a user-imported root. **Conclusion: Java TLS (`socket://`) is not possible on the Nokia 9300.** This matches gtrxac.fi/j2me/proxyless (S80: system TLS patch only, no certificate method). The only way to reach pubtran-backend.mapy.cz is the native TLS patch once [symbian-tls#13](https://github.com/shinovon/symbian-tls/issues/13) is fixed.

### Over-the-air install test

`ota/` has signed SignTest MIDlets and `ota_server.js`, a small HTTP server with the MIDP MIME types that rewrites `MIDlet-Jar-URL` to an absolute URL. Run `node ota_server.js` in that folder, then open `http://<PC address>:8000/` in the phone's browser and pick a `.jad`. SignTest's *Socket test* shows whether `socket://` is allowed.

## Building

This works exactly like the discord-j2me fork. Everything is bundled (JDK 8, ProGuard, stub API jars, KEmulator).

1. Install [Node.js](https://nodejs.org).
2. Run `build.bat` (Windows) or `build.sh` (Linux).

Output: `bin/pubtran_s80.jar` (unsigned, native TLS). Install the `.jar` as before.

**The jar is unsigned, and it has to be.** See [Why the jar can't be signed](#why-the-jar-cant-be-signed-nokia-9300) below.

**Every build gets a new `MIDlet-Version`.** `sdk/compile_all.js` raises it in `manifest.mf` (1.0.3 → 1.0.4 …), so install a new build over the old one as an update. The 9300 rejects a jar with the same name, vendor and version as a suite it already knows but different content, as "Neplatný archív aplikace" (invalid archive). This happened even after uninstalling, and whether the jar came over the browser or Bluetooth. A jar with a different `MIDlet-Name` installs too, as a second app. Earlier builds installed with Nokia Application Installer (PC Suite 6.6) may not have hit this. To build without the bump, set `NO_VERSION_BUMP=1`.

Install the `.jar` directly, not the `.jad`. Over the air, the 9300 rejects our `.jad` before it downloads the jar. The server in `ota/` (`start_ota_server.bat`, port 8000) serves the jar and the TLS DLLs, and has an `/upload` page for sending logs from the phone to the PC.

- `build.json` has these targets:
  - `pubtran_s80`: the release build (unsigned, native TLS), ProGuard-obfuscated.
  - `pubtran_s80_signed`: signed with the Darkman certificate, uses Java TLS. Disabled.
  - `pubtran_debug`: unobfuscated, disabled by default. Set `"disabled": false` to build it.
- The targets list `lib/bouncycastle.jar` in their bootclasspath: the build extracts it into `lib/bouncycastle/` once and bundles it (ProGuard keeps only what's used). The app's classes are added after it, so `src/org/bouncycastle/...` replaces the original classes.
- The targets compile against `cldcapi11.jar`, because the Nokia 9300 is CLDC 1.1 and FastRPC coordinates are doubles. The manifest declares CLDC-1.1.
- `build.sh` / `compile.sh` compile with `-encoding UTF-8`, because the sources contain Czech text.

## Why the jar can't be signed (Nokia 9300)

The Nokia 9300 / 9500 runs **Series 80 v2 on Symbian 7.0s (EKA1)**, not S60.

**What we tried.** Unsigned MIDlets get a `SecurityException` for `socket://`, so we tried signing. The 9300 refused every signed suite, even a 2 KB test MIDlet ("Instalace aplikace byla odmítnuta serverem jazyka Java" / "Digitální podpis nelze ověřit"). We tried three signers, each imported on the phone and allowed for application installation:

- the "Darkman" certificate from discord-j2me,
- an own self-signed certificate,
- an own root + signer chain.

**Why it can't work.** On Nokia phones of this generation, only root certificates built in by Nokia, the operators and the big CAs can verify a MIDlet signature. An imported certificate can be marked as trusted, but it never maps to a MIDP protection domain, so a signature chaining to it can't be verified.

- Forum Nokia's [MIDP 2.0: Tutorial On Signed MIDlets](https://wosign.com/Support/resources/MIDP_2_0_Tutorial_On_Signed_MIDlets_v1_1_en.pdf) says a self-signed certificate works only in the emulator: "the set of root certificates is closed".
- [gtrxac.fi/j2me/proxyless](https://gtrxac.fi/j2me/proxyless) supports Series 80 2nd Edition (Symbian 7.0) only through the system-level TLS 1.2 patch ("certificate is not required"), not through Java TLS with a certificate.
- The workarounds for newer phones need Symbian 9.x (S60v3 and later): discord-j2me's Darkman certificate and nnproject's [Java Permissions patch](http://nnproject.cc/jrtsecuritypatch) (Symbian 9.3+ with Open4All).
- The nnproject [TLS 1.2 patch](http://nnproject.cc/tls) has an EKA1 build (BearSSL) for S60v2, **S80v2**, S90 and UIQ2, for native and J2ME apps. That's what this app uses, through the fork [janseris/symbian-tls](https://github.com/janseris/symbian-tls) (branch `eka1-java-fixes`).

**Consequences for the app.** The app runs in the *untrusted* domain:

- **No sockets.** No `socket://` or `ssl://`, so no TLS or raw TCP of our own. The BouncyCastle Java TLS (`JavaTls`) can't run on the phone. It stays behind `//#ifdef JAVA_TLS`, in the disabled targets `pubtran_s80_signed` and `pubtran_debug`, for emulators and other phones.
- **All HTTPS goes through `HttpConnection` (`https://`), handled by the phone's `SSLADAPTOR.dll`.** The app can't choose the TLS version, cipher suites or SNI, and can't check the certificate itself. SNI and these settings come from the patched DLL; for Java the DLL reads the host name from the request's `Host:` header.
- **No connection reuse from Java (as observed).** The SSL log shows a new TCP connection and a full TLS handshake for every `HttpConnection`, and the DLL is unloaded after each one, so the app can't keep a connection open between requests. Two things can still make repeated requests cheaper, and neither is a reused connection. *TLS session resumption* lets a later connection to the same server skip the expensive key exchange: the client offers the session ID from the previous handshake, and the server can accept it, which saves the ECDHE/RSA maths on the phone. Because the DLL is unloaded, the patch saves the session in `C:\System\Data\ssl_sessions.dat`. This is not verified on the phone yet. The other way is fewer and smaller requests.
- **No built-in gzip.** CLDC 1.1 has no `java.util.zip`, so the app doesn't send `Accept-Encoding: gzip`. This costs nothing for pubtran: the capture of the Android app (`pubtran.har` in the main repo) sends `Accept-Encoding: gzip`, yet all 34 backend responses come back uncompressed (`application/x-frpc-rest`, no `Content-Encoding`). For other servers, a small pure-Java inflater could be bundled.
- **Permission prompts.** The phone may ask the user to allow network access, and the user can't grant the app a "trusted" level.
- **No signing keys.** None are needed; build only the unsigned `pubtran_s80` target.

## Testing in KEmulator

- Double-click `run_kemulator.bat`, or in VS Code use *Run Jizdni rady (Nokia 9300 / S80)*.
- The bundled KEmulator defaults to the `640x200 (Nokia 9300/9500 - Series 80)` preset.
- In the emulator, "native TLS" means the PC's Java HTTPS.
