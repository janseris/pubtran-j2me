# Lessons from the Nokia 9300: why "Hledat" hung, and rules to avoid it

The Nokia 9300 runs Series 80 v2 on Symbian 7.0s (EKA1), with Java ME (MIDP 2.0 / CLDC 1.1).
HTTPS goes through `HttpConnection` and the patched `SSLADAPTOR.dll`
([janseris/symbian-tls](https://github.com/janseris/symbian-tls), branch `eka1-java-fixes`).
The TLS patch runs inside the Java networking thread, `jes-dd-java-comms`.

## What happened (October 2026, app 1.0.5 to 1.0.13)

**Symptom.** Suggestions (Odkud / Kam) worked. Pressing **Hledat** on the start screen often hung on
"Připojování… (getroutesopt)" with no network traffic. It ended in a timeout or a crash
(`jes-dd-java-comms`, KERN-EXEC 3), and sometimes the whole phone froze afterwards.

**How it was narrowed down:**

- **What it isn't:**
  - **The server or the route:** the same search, with the same stations (55 KB response), worked
    every time from the HTTPS test screen (*Otestovat hledání (POST)*), also after a pause. The
    server answered in ~250 ms when the request reached it.
  - **The request format:** Java sends the search as two writes (headers 234 B, then the body
    ~325 B), and this works.
- **What failed:** only Hledat on the start screen. Its connections either stalled with no answer
  (until the server closed the idle connection after ~60 s) or were closed right after the
  ClientHello.
- **What fixed it:** after 1.0.13 changed only the start screen's loading animation, every search
  worked (14 requests, 2.7–4 s each, no retries needed).

**Most likely cause: the start screen's loading overlay.** While a request ran, a ticker thread
called `repaint()` on the **whole screen every 120 ms**. Each repaint redrew all tiles, a
~100-line stipple over the 640×200 screen, and the spinner. On the 9300's slow CPU this kept the
Java VM busy, and the TLS patch, which runs in the Java networking thread, apparently didn't get
enough time. Handshakes and reads stalled until the server or the link gave up. The HTTPS test
screen's loading screen draws much less, which is why the same request worked there. This wasn't
proven directly (there is no profiler on the phone); it is the one difference whose removal made
the problem go away.

**Bugs that made it worse (all fixed):**

| Bug | Effect | Fix |
|---|---|---|
| After a failed handshake, the TLS patch left the next Send/Recv pending forever | the app hung on "Připojování", the phone froze | ssladaptor v10: the handshake error is kept, and every later request fails with it at once |
| "Zrušit" and the watchdog closed the `HttpConnection` from another thread | KERN-EXEC 3 in `jes-dd-java-comms`, freeze on the next request | never close it from another thread; abandon the request instead (1.0.8) |
| 30 s timeout | dropped a slow but successful response (it arrived after 39 s) | stall detection plus retry (1.0.12) |
| Several requests at once (suggest-as-you-type + search) | two TLS connections through the patch at the same time | one request at a time (1.0.8) |
| The verbose log build (`SSL_LOG_VERBOSE`) is very slow (~15 ms per line) | hid the timing problem: with it, Hledat worked | test with the quiet log build (one summary line per connection) |

## Rules for this app

1. **No full-screen repaints in a loop while a request runs.** Draw the dimmed background once,
   then repaint only the small area that changes (`repaint(x, y, w, h)`; see
   `StartScreen.spinnerBox()`). Keep animation ticks at 120 ms or slower.
2. **Never close an `HttpConnection` or its streams from another thread** while a request is in
   progress. To cancel, give the UI back and ignore the late result (`RequestThread.cancelActive`,
   `PubtranApi.cancel`).
3. **Every request needs a way out:**
   - `PubtranApi.call()` runs each attempt on its own thread.
   - An attempt with no sign of life for 15 s (`STALL_TIMEOUT_MS`) is abandoned, and a new
     connection is tried, up to 3 times (`ATTEMPTS`).
   - It also retries after an `IOException` before the response.
   - "Zrušit" (menu or Esc) ends it at once.
4. **One request at a time** (`PubtranApi.acquire()`), with a bounded wait.
5. **Keep requests few.** Each `HttpConnection` is a new TCP and TLS connection (~1–1.3 s
   handshake). Java can't keep a connection open between requests.
6. **Diagnose with the tools in the app:**
   - Request log → *Odeslat log na PC*. It is kept in RMS, so it survives a freeze. Each request
     shows its phases; on this phone DNS, TCP and TLS all happen inside `getResponseCode`.
   - HTTPS test → *Otestovat hledání (POST)* runs the same API calls from a different screen.
   - Use the quiet SSL log DLL (`ssladaptor_log.dll`), not the verbose one, for timing problems.
     It writes every line to disk right away, so a crash doesn't lose the last lines.
7. **Installing:**
   - Every build needs a new `MIDlet-Version` (major.minor, raised by `sdk/compile_all.js`). The
     9300 rejects a jar with a known name and version but different content, reporting
     "Neplatný archív aplikace".
   - Keep the `.jad` and `.jar` from the same build together: PC Suite uses the `.jad`.
