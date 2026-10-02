package pubtran;


import fi.gtrxac.bluewap.http.HTTP;
import java.util.Vector;

/**
 * Client for https://pubtran-backend.mapy.cz/api/v1/ - the backend of the Android app
 * "Jízdní řády" (cz.fhejl.pubtran), reverse-engineered from a mitmproxy capture (see
 * PubtranClient/API.md next to the phoneapk capture).
 *
 * Every call is POST {BASE_URL}{method} with a FastRPC-encoded struct as the body
 * (Content-Type/Accept: application/x-frpc-rest) - no API key, token or signature.
 * Transport: see USE_JAVA_TLS - the phone's native TLS (NativeHttp) or, while the EKA1
 * TLS patch can't reach this server from Java, a kept-alive BouncyCastle connection
 * (JavaTls).
 *
 * Like the old JSONPlaceholder test client, every request updates the live progress
 * fields read by the loading overlay (progressText()) and is recorded into RequestLog with
 * its timing, size and TLS details.
 */
public class PubtranApi {
    public static final String BASE_URL = "https://pubtran-backend.mapy.cz/api/v1/";
    private static final String CONTENT_TYPE = "application/x-frpc-rest";

    /**
     * true: requests go over JavaTls (BouncyCastle TLS over socket://, one connection
     * kept open and reused). false: over the phone's native TLS (HttpConnection https://,
     * SSLADAPTOR.dll). The native path currently stalls the Nokia 9300 on this server
     * (see the EKA1 TLS patch issue), so the Java path is used until the patch is fixed.
     *
     * socket:// is only allowed for signed MIDlets on the Nokia 9300 (unsigned ones get a
     * SecurityException), so only builds with JAVA_TLS (the signed one, installed via the JAD with the
     * signing certificate trusted on the phone) uses Java TLS.
     */
//#ifdef JAVA_TLS
    public static final boolean USE_JAVA_TLS = true;
//#else
    public static final boolean USE_JAVA_TLS = false;
//#endif

    /** How long the most recently completed request took, in milliseconds. */
    public static long lastRequestDurationMs;

    // Live progress of the request in flight - see progressText().
    public static boolean connecting;
    public static int bytesTransferred;
    public static int bytesTotal = -1;
    public static long requestStartTime;
    /** Which call is in flight, e.g. "getroutesopt" - shown under the spinner. */
    public static String currentMethod = "";

    // ============================== cancel / timeout ==============================

    /**
     * No sign of life for this long (connecting, waiting for the response, between body
     * chunks) gives up on that connection and tries a new one. On the 9300 a request
     * answers in 2-8 s; a stuck one (seen: the server or link closing the connection
     * after the ClientHello, or no answer at all) never recovers, while a new connection
     * right after it works.
     */
    public static final int STALL_TIMEOUT_MS = 15000;
    /** Connections tried per call (after a stall or a connection error). */
    public static final int ATTEMPTS = 3;
    /** Which attempt is running (shown under the spinner from the 2nd one). */
    public static volatile int attemptNo;
    /** The request in flight (native transport), so cancel() can close it. */
    private static volatile NativeHttp current;
    /** Set by cancel(): the reason the request in flight was aborted. */
    private static volatile String cancelReason;
    /** True when the last failed request was cancelled by the user (no error alert). */
    public static volatile boolean cancelledByUser;
    /** Time of the last sign of life of the request in flight (start, progress). */
    private static volatile long lastActivity;

    /**
     * Gives up on the request in flight ("Zrušit" or the stall watchdog). It is NOT
     * closed: closing an HttpConnection from another thread while the Java comms thread
     * is inside the TLS patch crashed the 9300 (KERN-EXEC 3 in jes-dd-java-comms) or
     * froze it on the next request. The request is left to finish or fail on its own;
     * its result is ignored (RequestThread.cancelActive / stall).
     */
    public static void cancel(String reason, boolean byUser) {
        if (cancelReason != null) return;
        cancelReason = reason;
        cancelledByUser = byUser;
    }

    public static boolean isBusy() {
        return current != null;
    }

    // ============================== plumbing ==============================

    /**
     * One FastRPC call over the transport chosen by USE_JAVA_TLS.
     * Returns the decoded response struct. Every call - successful or not - is
     * recorded in RequestLog with URL, headers, status, timing and TLS details.
     */
    private static final Object LOCK = new Object();
    private static boolean inFlight;

    /**
     * One request at a time (a suggest typed in the place picker and a search must not
     * run two TLS connections through the patch at once). Waits for the previous one,
     * but at most STALL_TIMEOUT_MS: an abandoned, stuck request mustn't block forever.
     */
    private static void acquire() {
        synchronized (LOCK) {
            long end = System.currentTimeMillis() + STALL_TIMEOUT_MS;
            while (inFlight && System.currentTimeMillis() < end) {
                try { LOCK.wait(500); } catch (InterruptedException e) {}
            }
            inFlight = true;
        }
    }

    private static void release() {
        synchronized (LOCK) {
            inFlight = false;
            LOCK.notifyAll();
        }
    }

    /** One connection attempt of call(), run on its own thread so call() can give up on it. */
    private static class Attempt extends Thread {
        private final String method;
        private final FrpcStruct params;
        FrpcStruct result;
        Exception error;
        boolean done;

        Attempt(String method, FrpcStruct params) {
            this.method = method;
            this.params = params;
        }

        public void run() {
            try {
                result = callLocked(method, params);
            }
            catch (Exception e) {
                error = e;
            }
            synchronized (this) {
                done = true;
                notifyAll();
            }
        }
    }

    /**
     * One API call: up to ATTEMPTS connections. A connection that shows no sign of life
     * for STALL_TIMEOUT_MS is abandoned (NOT closed - closing it from another thread
     * crashed the 9300) and a new one is tried; so is one that fails with an I/O error
     * before the response. "Zrušit" (cancel) ends the call at once.
     */
    public static FrpcStruct call(String method, FrpcStruct params) throws Exception {
        acquire();
        cancelReason = null;
        cancelledByUser = false;
        try {
            Exception last = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                attemptNo = attempt;
                Attempt a = new Attempt(method, params);
                lastActivity = System.currentTimeMillis();
                a.start();
                synchronized (a) {
                    while (!a.done && cancelReason == null
                            && System.currentTimeMillis() - lastActivity < STALL_TIMEOUT_MS) {
                        try { a.wait(250); } catch (InterruptedException e) {}
                    }
                }
                if (cancelReason != null) throw new Exception(cancelReason);
                if (a.done) {
                    if (a.error == null) return a.result;
                    last = a.error;
                    if (!(a.error instanceof java.io.IOException) || attempt == ATTEMPTS) throw a.error;
                    RequestLog.persist(new java.util.Date().toString() + "  " + method + ": pokus " + attempt
                        + " selhal (" + a.error + "), nové spojení\n");
                }
                else {
                    NativeHttp r = current;
                    RequestLog.persist(new java.util.Date().toString() + "  ZASEKNUTO: " + method + ", pokus " + attempt
                        + ", fáze: " + (r != null ? phases(r) : "?") + " - nové spojení\n");
                    last = new Exception("Server neodpověděl (" + attempt + "x " + (STALL_TIMEOUT_MS / 1000) + " s bez odezvy)");
                }
            }
            throw last;
        }
        finally {
            attemptNo = 0;
            release();
        }
    }

    private static FrpcStruct callLocked(String method, FrpcStruct params) throws Exception {
        byte[] body = Frpc.encode(params);
        String url = BASE_URL + method;

        currentMethod = method;
        connecting = true;
        bytesTransferred = 0;
        bytesTotal = -1;
        requestStartTime = System.currentTimeMillis();

        LogEntry e = new LogEntry();
        e.timestamp = requestStartTime;
        e.method = "POST";
        e.path = method;
        e.url = url;
        e.requestBytes = body.length;

        Vector headers = new Vector();
        headers.addElement(new String[] {"User-Agent", "okhttp/5.4.0"});
        headers.addElement(new String[] {"Accept", CONTENT_TYPE});
        headers.addElement(new String[] {"Content-Type", CONTENT_TYPE});

        byte[] response;
        int code;
        try {
            response = send(url, headers, body, e);
            code = statusCode(e.statusLine);
        }
        catch (Exception ex) {
            finish(e, 0, false, ex.toString());
            throw ex;
        }

        if (code >= 400 || code < 0) {
            finish(e, response.length, false, e.statusLine);
            throw new Exception("HTTP " + code + " (" + method + ")");
        }

        Object decoded;
        try {
            decoded = Frpc.decode(response);
        }
        catch (Exception ex) {
            finish(e, response.length, false, ex.getMessage());
            throw ex;
        }
        if (!(decoded instanceof FrpcStruct)) {
            finish(e, response.length, false, "odpověď není struct");
            throw new Exception("Neočekávaná odpověď (" + method + ")");
        }
        finish(e, response.length, true, null);
        return (FrpcStruct) decoded;
    }

    /** Sends the request over the transport chosen by USE_JAVA_TLS. */
    private static byte[] send(String url, Vector headers, byte[] body, LogEntry e) throws Exception {
//#ifdef JAVA_TLS
        if (USE_JAVA_TLS) return sendJava(url, headers, body, e);
//#endif
        return sendNative(url, headers, body, e);
    }

//#ifdef JAVA_TLS
    private static final HTTP.ProgressListener PROGRESS = new HTTP.ProgressListener() {
        public void onConnecting() {
            connecting = true;
        }
        public void onProgress(int bytesRead, int total) {
            connecting = false;
            bytesTransferred = bytesRead;
            bytesTotal = total;
        }
    };

    /** Over the shared keep-alive JavaTls connection. */
    private static byte[] sendJava(String url, Vector headers, byte[] body, LogEntry e) throws Exception {
        e.tlsLabel = JavaTls.LABEL;
        StringBuffer rh = new StringBuffer("POST " + url + "\n");
        for (int i = 0; i < headers.size(); i++) {
            String[] h = (String[]) headers.elementAt(i);
            rh.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        rh.append("Content-Length: ").append(body.length).append("\nConnection: keep-alive");
        e.requestHeaders = rh.toString();

        JavaTls.Response r = JavaTls.exchange("POST", url, headers, body, PROGRESS);
        e.reusedConnection = r.reused;
        e.connectMs = r.reused ? -1 : r.connectMs;
        e.handshakeMs = r.reused ? -1 : r.handshakeMs;
        e.resumedSession = r.resumedSession;
        e.waitMs = r.waitMs;
        e.downloadMs = r.downloadMs;
        e.statusLine = r.statusLine;
        e.responseHeaders = r.headers;
        e.tlsInfo = r.tls;
        return r.body;
    }

//#endif

    /** Sends the request via NativeHttp (StandardHTTP over HttpConnection). */
    private static byte[] sendNative(String url, Vector headers, byte[] body, LogEntry e) throws Exception {
        final NativeHttp req = new NativeHttp("POST", url);
        StringBuffer rh = new StringBuffer("POST " + url + "\n");
        for (int i = 0; i < headers.size(); i++) {
            String[] h = (String[]) headers.elementAt(i);
            req.setHeader(h[0], h[1]);
            rh.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        req.setData(body);
        rh.append("Content-Length: ").append(body.length);
        e.requestHeaders = rh.toString();
        e.tlsLabel = NativeHttp.LABEL;

        req.setProgressListener(new HTTP.ProgressListener() {
            public void onConnecting() {
                connecting = true;
            }
            public void onProgress(int bytesRead, int total) {
                connecting = false;
                bytesTransferred = bytesRead;
                bytesTotal = total;
                lastActivity = System.currentTimeMillis();
            }
        });

        current = req;
        lastActivity = System.currentTimeMillis();

        long t = System.currentTimeMillis();
        try {
            req.getResponseCode();
            lastActivity = System.currentTimeMillis();
            e.waitMs = System.currentTimeMillis() - t; // connect + TLS + request + server time (HttpConnection hides the parts)
            req.fillResponseInfo(e);
            // Read TLS info now - reading the body closes the connection, and a closed
            // HttpsConnection can't return its SecurityInfo anymore.
            e.tlsInfo = req.captureTlsInfo();
            long t2 = System.currentTimeMillis();
            byte[] r = req.getResponseBytesWithProgress();
            e.downloadMs = System.currentTimeMillis() - t2;
            if (cancelReason != null) throw new Exception(cancelReason);
            return r;
        }
        catch (Exception ex) {
            // the connection was closed by cancel(): report why, not the I/O error it caused
            if (cancelReason != null) throw new Exception(cancelReason);
            throw ex;
        }
        finally {
            e.phases = phases(req);
            if (current == req) current = null;
            // failed before the status arrived: try anyway (the handshake may have completed)
            if (e.tlsInfo == null) e.tlsInfo = req.captureTlsInfo();
        }
    }

    /** "open 5 ms, výstup 38200 ms, zápis 40 ms, odpověď 900 ms" - each phase's duration. */
    static String phases(fi.gtrxac.bluewap.http.HTTP r) {
        if (r.tStart == 0) return "nezačal";
        StringBuffer sb = new StringBuffer();
        long prev = r.tStart;
        long[] t = {r.tOpened, r.tStreamOpened, r.tWritten, r.tResponse};
        String[] n = {"Connector.open", "openOutputStream (DNS+TCP+TLS?)", "zápis těla", "čekání na odpověď"};
        for (int i = 0; i < t.length; i++) {
            if (sb.length() > 0) sb.append(", ");
            if (t[i] == 0) {
                sb.append(n[i]).append(" nedokončeno po ").append(System.currentTimeMillis() - prev).append(" ms");
                break;
            }
            sb.append(n[i]).append(' ').append(t[i] - prev).append(" ms");
            prev = t[i];
        }
        return sb.toString();
    }

    /** Status code from a status line like "HTTP 200 OK", or -1. */
    static int statusCode(String statusLine) {
        try {
            int a = statusLine.indexOf(' ');
            return Integer.parseInt(statusLine.substring(a + 1, a + 4));
        }
        catch (Exception e) {
            return -1;
        }
    }

    private static void finish(LogEntry e, int received, boolean ok, String error) {
        lastRequestDurationMs = System.currentTimeMillis() - requestStartTime;
        e.durationMs = lastRequestDurationMs;
        e.responseBytes = received;
        e.success = ok;
        e.errorMessage = error;
        RequestLog.add(e);
    }

    /**
     * Short status line for the loading UI: "Připojování...", "4.2 KB / 12.0 KB (~1s)",
     * or "3.1 KB přijato" when the server didn't send a Content-Length.
     */
    public static String progressText() {
//#ifdef JAVA_TLS
        String phase = JavaTls.phase;
        if (phase != null) {
            long s = (System.currentTimeMillis() - JavaTls.phaseStart) / 1000;
            return phase + "... " + s + " s (" + currentMethod + ")";
        }
//#endif
        if (connecting) {
            return "Připojování... (" + currentMethod + (attemptNo > 1 ? ", pokus " + attemptNo : "") + ")";
        }
        if (bytesTotal <= 0) {
            return formatKB(bytesTransferred) + " přijato";
        }
        String text = formatKB(bytesTransferred) + " / " + formatKB(bytesTotal);
        long elapsed = System.currentTimeMillis() - requestStartTime;
        if (bytesTransferred > 0 && bytesTransferred < bytesTotal && elapsed > 300) {
            long remainingMs = elapsed * (bytesTotal - bytesTransferred) / bytesTransferred;
            int remainingSec = (int) ((remainingMs + 999) / 1000);
            if (remainingSec > 0) text += " (~" + remainingSec + " s)";
        }
        return text;
    }

    public static String formatKB(int bytes) {
        if (bytes < 1024) return bytes + " B";
        return (bytes / 1024) + "." + ((bytes % 1024) * 10 / 1024) + " KB";
    }

    private static Vector langArray() {
        Vector v = new Vector();
        v.addElement("cs");
        return v;
    }

    // ============================== endpoints ==============================

    /**
     * suggest: place autocomplete. lat/lon (may be null) only bias the ranking - the
     * app sends the phone's position; we send the other end of the search if known.
     */
    public static Vector suggest(String query, Double lat, Double lon) throws Exception {
        return parseSuggest(call("suggest", suggestParams(query, lat, lon)));
    }

    static FrpcStruct suggestParams(String query, Double lat, Double lon) {
        FrpcStruct p = new FrpcStruct();
        p.put("query", query);
        p.put("count", 10);
        if (lon != null) p.put("lon", lon);
        p.put("category", "municipality|street|address|poi|area|firm|pubt");
        p.put("lang", "cs");
        if (lat != null) p.put("lat", lat);
        return p;
    }

    static Vector parseSuggest(FrpcStruct r) {
        Vector items = r.getArray("result");
        Vector out = new Vector();
        for (int i = 0; i < items.size(); i++) {
            FrpcStruct it = FrpcStruct.structAt(items, i);
            if (it != null) out.addElement(Place.fromSuggest(it));
        }
        return out;
    }

    /**
     * getroutesopt: connection search. Paging like the app: keep the same "when",
     * index 0 = first page, +COUNT = later, -COUNT = earlier, and hashesUsed = hashes
     * of the connections already shown (the server skips them).
     */
    public static Vector search(SearchState s, String when, int index, Vector hashesUsed) throws Exception {
        return parseSearch(call("getroutesopt", searchParams(s, when, index, hashesUsed)));
    }

    static FrpcStruct searchParams(SearchState s, String when, int index, Vector hashesUsed) {
        FrpcStruct flags = new FrpcStruct();
        flags.put("bus", s.bus ? 1 : 0);
        flags.put("unixt", 1);
        flags.put("mobile", 1);
        flags.put("lowfloor", s.lowFloor ? 1 : 0);
        flags.put("version", 3);
        flags.put("bike", 0);
        flags.put("trolley", s.trolley ? 1 : 0);
        flags.put("metro", s.metro ? 1 : 0);
        flags.put("ferry", s.ferry ? 1 : 0);
        flags.put("hashes_used", hashesUsed);
        flags.put("geometry", 0);
        flags.put("stroller", 0);
        flags.put("tram", s.tram ? 1 : 0);
        flags.put("cable", s.cable ? 1 : 0);
        flags.put("train", s.train ? 1 : 0);
        if (s.onlyDirect) flags.put("tcount", 0);

        FrpcStruct opts = new FrpcStruct();
        opts.put("start", s.from.toFrpc());
        opts.put("count", SearchState.COUNT);
        opts.put("index", index);
        opts.put("end", s.to.toFrpc());
        opts.put("lang", langArray());
        if (s.via != null) {
            Vector mid = new Vector();
            mid.addElement(s.via.toFrpc());
            opts.put("mid", mid);
        }

        FrpcStruct p = new FrpcStruct();
        p.put("isDeparture", s.isDeparture);
        p.put("flags", flags);
        p.put("searchOpts", opts);
        p.put("when", when);
        return p;
    }

    static Vector parseSearch(FrpcStruct r) throws Exception {
        int status = r.getInt("status", 200);
        if (status != 200) throw new Exception("Vyhledávání selhalo (status " + status + ")");

        Vector stops = r.getArray("stops");
        Vector agencies = r.getArray("agenciesinfo");
        Vector routes = r.getArray("routes");
        Vector out = new Vector();

        for (int i = 0; i < routes.size(); i++) {
            FrpcStruct route = FrpcStruct.structAt(routes, i);
            if (route == null) continue;
            Route c = new Route();
            c.hash = route.getLong("hash", 0);
            c.departureIso = route.getString("departureISO", "");
            c.arrivalIso = route.getString("arrivalISO", "");
            c.durationSeconds = route.getInt("time", 0);
            c.transferCount = route.getInt("transferCount", 0);

            Vector items = route.getArray("items");
            for (int j = 0; j < items.size(); j++) {
                FrpcStruct it = FrpcStruct.structAt(items, j);
                if (it == null) continue;
                int type = it.getInt("type", 0);
                if (type == 0) {
                    c.parts.addElement(RoutePart.rideFrom(it, stops, agencies));
                }
                else {
                    RoutePart w = new RoutePart();
                    w.kind = type == 1 ? RoutePart.WALK : RoutePart.WAIT;
                    w.departureIso = it.getString("departureISO", "");
                    w.arrivalIso = it.getString("arrivalISO", "");
                    w.distance = it.getInt("distance", 0);
                    c.parts.addElement(w);
                }
            }

            Vector pay = route.getArray("payment");
            for (int j = 0; j < pay.size(); j++) {
                FrpcStruct pm = FrpcStruct.structAt(pay, j);
                if (pm != null) c.payments.addElement(Fmt.payment(pm));
            }
            out.addElement(c);
        }
        return out;
    }

    /**
     * getnextdepartures: another run of the same line between the same stops (the app's
     * swipe). offset is relative to the ORIGINAL ride: -1 previous, 1 next, 2 the one
     * after... Returns null if the server has no such run.
     */
    public static RoutePart otherRun(RoutePart original, int offset, SearchState modes) throws Exception {
        return parseOtherRun(call("getnextdepartures", otherRunParams(original, offset, modes)));
    }

    static FrpcStruct otherRunParams(RoutePart original, int offset, SearchState modes) {
        FrpcStruct o = new FrpcStruct();
        o.put("bus", modes.bus ? 1 : 0);
        o.put("unixt", 1);
        o.put("lowfloor", modes.lowFloor ? 1 : 0);
        o.put("trolley", modes.trolley ? 1 : 0);
        o.put("endindex", original.endIndex);
        o.put("startindex", original.startIndex);
        o.put("metro", modes.metro ? 1 : 0);
        o.put("ferry", modes.ferry ? 1 : 0);
        o.put("geometry", 0);
        o.put("stops", 1);
        o.put("tripdata", 1);
        o.put("time", original.departureIso.length() >= 19 ? original.departureIso.substring(0, 19) : original.departureIso);
        o.put("lang", langArray());
        o.put("tram", modes.tram ? 1 : 0);
        o.put("cable", modes.cable ? 1 : 0);
        o.put("reqindex", offset);
        o.put("train", modes.train ? 1 : 0);

        Vector pair = new Vector();
        pair.addElement(original.partDescription);
        pair.addElement(o);
        Vector params = new Vector();
        params.addElement(pair);
        return new FrpcStruct().put("params", params);
    }

    static RoutePart parseOtherRun(FrpcStruct r) {
        Vector results = r.getArray("results");
        if (results.size() == 0) return null;
        FrpcStruct res = FrpcStruct.structAt(results, 0);
        if (res == null || res.getInt("status", 0) != 200) return null;
        FrpcStruct trip = res.getStruct("trip");
        if (trip == null) return null;
        return RoutePart.rideFrom(trip, trip.getArray("stops"), trip.getArray("agenciesinfo"));
    }

    /**
     * gettripinfos: realtime delays / disruptions for the given rides (RoutePart each).
     * Fills each ride's liveInfo in place.
     */
    public static void refreshLive(Vector rides) throws Exception {
        if (rides.size() == 0) return;
        applyTripInfos(rides, call("gettripinfos", tripInfosParams(rides)));
    }

    static FrpcStruct tripInfosParams(Vector rides) {
        Vector ids = new Vector();
        for (int i = 0; i < rides.size(); i++) {
            RoutePart p = (RoutePart) rides.elementAt(i);
            FrpcStruct t = new FrpcStruct();
            t.put("tripId", p.partDescription);
            t.put("time", FrpcDate.fromIso(p.departureIso));
            ids.addElement(t);
        }
        return new FrpcStruct().put("tripIds", ids);
    }

    static void applyTripInfos(Vector rides, FrpcStruct r) {
        Vector results = r.getArray("results");
        for (int i = 0; i < results.size() && i < rides.size(); i++) {
            FrpcStruct res = FrpcStruct.structAt(results, i);
            if (res == null) continue;
            Vector inf = res.getArray("info");
            Vector live = new Vector();
            for (int j = 0; j < inf.size(); j++) {
                FrpcStruct s = FrpcStruct.structAt(inf, j);
                if (s != null) live.addElement(Info.from(s));
            }
            ((RoutePart) rides.elementAt(i)).liveInfo = live;
        }
    }
}
