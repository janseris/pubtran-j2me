package pubtran;

import com.gtrxac.discord.App;

import javax.microedition.lcdui.*;

/**
 * HTTPS test page: a GET to https://<server>/ over either transport the app has -
 * NativeHttp (HttpConnection, the phone's native TLS in SSLADAPTOR.dll) or JavaTls
 * (BouncyCastle over socket://, with SNI) - timed until the status line, and what the
 * connection reports about TLS: protocol, cipher suite and certificate issuer. The Java
 * path also times TCP and the handshake separately and repeats the GET over the same
 * connection to show what keep-alive saves.
 *
 * "Otestovat lehké stránky" runs every lightweight text site once in a row, to compare
 * which servers the phone's Java HTTPS can reach. The heavy ones (Seznam, Google,
 * pubtran-backend) are only run one by one - pubtran-backend stalls the phone until
 * the USB link drops.
 *
 * Buttons instead of screen commands: on S80 a focused field's CBA only shows its own
 * commands, so actions are button items and item commands on every field.
 */
public class TlsTestScreen extends Form implements CommandListener, ItemCommandListener, ItemStateListener,
        LoadingScreen.StatusSource {
    private static final Command RUN_ONE = new Command("Otestovat server", Command.ITEM, 1);
    private static final Command RUN_ALL = new Command("Otestovat lehké stránky", Command.ITEM, 2);
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 3);
    /** On the progress screen: back to the form while the test keeps running. */
    private static final Command HIDE_COMMAND = new Command("Skrýt", Command.BACK, 1);
    /** Every server is tested this many times in a row (does the 2nd request reuse anything?). */
    private static final int ATTEMPTS = 3;

    /**
     * The batch ("Otestovat lehké stránky"): tiny responses vs. a large one on servers whose
     * certificate the phone accepts or not, to tell "response too big" from "root CA
     * missing on the phone":
     *  - google/generate_204: same GlobalSign-rooted chain as jsonplaceholder, empty 204
     *  - jsonplaceholder /posts/1 (small, worked) vs / (its large HTML homepage)
     *  - robots.txt (small) on seznam (ISRG X1), duckduckgo (DigiCert G2), cnn (GlobalSign R5), npr (ISRG X1)
     */
    private static final String[] LIGHT = {
        "www.google.com/generate_204",
        "jsonplaceholder.typicode.com/posts/1",
        "jsonplaceholder.typicode.com/",
        "www.seznam.cz/robots.txt",
        "lite.duckduckgo.com/robots.txt",
        "lite.cnn.com/robots.txt",
        "text.npr.org/robots.txt",
        "example.com/",
    };
    /** Selectable one by one (not part of the batch). */
    private static final String[] HEAVY = {
        "www.seznam.cz",
        "www.google.com",
        "pubtran-backend.mapy.cz/api/v1/",
        // download speed: HTTPS with Content-Length (~87 KB, not chunked) vs. plain HTTP
        // (no TLS) - tells the TLS patch's cost apart from Java's HttpConnection's own
        "cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js",
        "http://speedtest.tele2.net/100KB.zip",
        // larger files: ~600 KB over HTTPS, 1 MB over plain HTTP
        "cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js",
        "http://speedtest.tele2.net/1MB.zip",
        "http://theoldnet.com/",
    };

    private final Displayable back;
    private final ChoiceGroup preset;
//#ifdef JAVA_TLS
    private final ChoiceGroup transport = new ChoiceGroup("TLS:", Choice.POPUP,
        new String[] {"Nativní (SSLADAPTOR.dll)", "Java (BouncyCastle)"}, null);
//#else
    private final ChoiceGroup transport = new ChoiceGroup("TLS:", Choice.POPUP,
        new String[] {"Nativní (SSLADAPTOR.dll)"}, null);
//#endif
    private final TextField host = new TextField("Server (cesta volitelná):", LIGHT[0], 160, TextField.URL);
    private final StringItem runOneButton = new StringItem(null, "Otestovat server", Item.BUTTON);
    private final StringItem runAllButton = new StringItem(null, "Otestovat lehké stránky", Item.BUTTON);
    private final StringItem status = new StringItem("Stav:", "Připraveno.");
    private final StringItem results = new StringItem("Výsledky:", "-");
    private final StringItem tls = new StringItem("TLS a certifikát (poslední úspěšný):", "-");

    private final StringBuffer lines = new StringBuffer();
    private volatile boolean running = false;

    // live progress, shown on the loading screen (statusLines())
    private LoadingScreen progressScreen;
    private volatile int curIndex, curTotal, curAttempt;
    private volatile String curName = "";
    private volatile String phase = "";
    private volatile int phaseStart, testStart;
    private static final long BASE = System.currentTimeMillis();

    /** Milliseconds since class load as an int (no long locals: the 9300's verifier is picky). */
    static int now() {
        return (int) (System.currentTimeMillis() - BASE);
    }
    private volatile int bytesRead = -1, bytesTotal = -1;

    public TlsTestScreen(Displayable back) {
        super("HTTPS test");
        this.back = back;

        String[] all = new String[LIGHT.length + HEAVY.length];
        for (int i = 0; i < LIGHT.length; i++) all[i] = LIGHT[i];
        for (int i = 0; i < HEAVY.length; i++) all[LIGHT.length + i] = HEAVY[i];
        preset = new ChoiceGroup("Předvolba:", Choice.POPUP, all, null);

        StringItem info = new StringItem(null,
            "GET https://server/ přes nativní TLS (HttpConnection) nebo Java TLS "
            + "(BouncyCastle přes socket://, posílá SNI). Ukáže čas do odpovědi, HTTP status "
            + "a TLS (protokol, šifra, vydavatel). Java TLS navíc rozepíše TCP / handshake "
            + "a pošle 2. požadavek stejným spojením (keep-alive).");
        info.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));

        runOneButton.setDefaultCommand(RUN_ONE);
        runAllButton.setDefaultCommand(RUN_ALL);
        Item[] focusable = {runOneButton, runAllButton, transport, preset, host};
        for (int i = 0; i < focusable.length; i++) {
            if (focusable[i] != runOneButton) focusable[i].addCommand(RUN_ONE);
            if (focusable[i] != runAllButton) focusable[i].addCommand(RUN_ALL);
            focusable[i].setItemCommandListener(this);
        }
        runOneButton.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE);
        runAllButton.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_AFTER);

        append(runOneButton);  // first focusable item -> focused when the screen opens
        append(runAllButton);
        append(info);
        append(transport);
        append(preset);
        append(host);
        append(status);
        append(results);
        append(tls);

        addCommand(BACK_COMMAND);
        setCommandListener(this);
        setItemStateListener(this);
    }

    /** Picking a preset fills in the server field. */
    public void itemStateChanged(Item item) {
        if (item == preset && preset.getSelectedIndex() >= 0) {
            int i = preset.getSelectedIndex();
            host.setString(i < LIGHT.length ? LIGHT[i] : HEAVY[i - LIGHT.length]);
        }
    }

    private void start(final String[] targets) {
        if (running) return;
        running = true;
        runOneButton.setText("Test probíhá...");
        runAllButton.setText("");
        progressScreen = new LoadingScreen("HTTPS test", this);
        progressScreen.addCommand(HIDE_COMMAND);
        progressScreen.setCommandListener(this);
        App.disp.setCurrent(progressScreen);
        new Thread() {
            public void run() {
                try {
                    curTotal = targets.length * ATTEMPTS;
                    for (int i = 0; i < targets.length; i++) {
                        for (int a = 1; a <= ATTEMPTS; a++) {
                            curIndex = i * ATTEMPTS + a;
                            curAttempt = a;
                            curName = targets[i];
                            status.setText(curIndex + "/" + curTotal + ": " + targets[i] + " (" + a + ". pokus) ...");
                            testOne(targets[i], a);
                        }
                    }
                    status.setText("Hotovo.");
                }
                catch (Throwable e) {
                    status.setText("Chyba testu: " + e);
                }
                running = false;
                runOneButton.setText("Otestovat server");
                runAllButton.setText("Otestovat lehké stránky");
                if (App.disp.getCurrent() == progressScreen) App.disp.setCurrent(TlsTestScreen.this);
                progressScreen = null;
            }
        }.start();
    }

    private void setPhase(String p) {
        phase = p;
        phaseStart = now();
    }

    /** Lines under the spinner on the progress screen. */
    public String[] statusLines() {
        int now = now();
        String head = curIndex + "/" + curTotal + ": " + curName;
        String attempt = curAttempt + ". pokus" + (curAttempt > 1 ? " (stejný server znovu)" : "")
            + ", celkem " + ((now - testStart) / 1000) + " s";
        String ph = phase + " - " + ((now - phaseStart) / 1000) + " s";
        String data = bytesRead < 0 ? "" : "Přijato " + PubtranApi.formatKB(bytesRead)
            + (bytesTotal > 0 ? " / " + PubtranApi.formatKB(bytesTotal) : "");
        return new String[] {head, attempt, ph, data};
    }

    private static int parseLen(String len) {
        if (len == null) return -1;
        try { return Integer.parseInt(len.trim()); }
        catch (Throwable e) { return -1; }
    }

    /** One GET https://target - timed until the status line, TLS info read before closing. */
    private void testOne(String target, int attempt) {
        String t = target.trim();
        String scheme = "https://";
        if (t.startsWith("https://")) t = t.substring(8);
        else if (t.startsWith("http://")) { // plain HTTP (no TLS) for comparison
            t = t.substring(7);
            scheme = "http://";
        }
        if (t.indexOf('/') < 0) t = t + "/";
        String url = scheme + t;
        String name = (attempt > 1 ? "#" + attempt + " " : "") + ("http://".equals(scheme) ? "http://" : "")
            + (t.endsWith("/") ? t.substring(0, t.length() - 1) : t);
        testStart = now();
        bytesRead = -1;
        bytesTotal = -1;
//#ifdef JAVA_TLS
        if (transport.getSelectedIndex() == 1) {
            testJava(url, name);
            return;
        }
//#endif

        NativeHttp req = new NativeHttp("GET", url);
        req.setProgressListener(new fi.gtrxac.bluewap.http.HTTP.ProgressListener() {
            public void onConnecting() {}
            public void onProgress(int read, int total) {
                bytesRead = read;
                bytesTotal = total;
            }
        });
        int t0 = now();
        StringBuffer line = new StringBuffer(name).append(": ");
        try {
            // HttpConnection does DNS, TCP, the TLS handshake (deferred to the first send
            // by the patched SSLADAPTOR.dll), sending and waiting in one blocking call
            setPhase("Připojení + TLS + odeslání + čekání na odpověď");
            int code = req.getResponseCode();
            int ms = now() - t0;
            TlsInfo info = req.captureTlsInfo(); // before close: a closed connection has no SecurityInfo
            String len = null, te = null;
            try {
                len = req.getResponseHeader("Content-Length");
                te = req.getResponseHeader("Transfer-Encoding");
            }
            catch (Throwable e) {}
            line.append("HTTP ").append(code).append(", ").append(ms).append(" ms");
            if (len != null) line.append(", ").append(len).append(" B");
            else if (te != null) line.append(", ").append(te);
            // read the body too: it goes through the patch's read path (512-byte chunks)
            setPhase("Čtení odpovědi");
            int t2 = now();
            try {
                // streamed and only counted, not kept: large test files (1 MB) would not
                // fit into the phone's Java heap
                bytesTotal = parseLen(len);
                bytesRead = 0;
                java.io.InputStream in = req.getResponseStream();
                byte[] buf = new byte[4096];
                int got = 0, n;
                while ((n = in.read(buf)) != -1) {
                    got += n;
                    bytesRead = got;
                }
                int bms = now() - t2;
                line.append(", tělo ").append(PubtranApi.formatKB(got)).append(" za ")
                    .append(bms).append(" ms");
                if (got >= 4096 && bms > 0) {
                    line.append(" (").append(got * 10 / bms * 100 / 1024).append(" KB/s)");
                }
            }
            catch (Throwable e) {
                line.append(", čtení těla CHYBA po ").append(now() - t2)
                    .append(" ms (").append(bytesRead < 0 ? 0 : bytesRead).append(" B): ").append(e.toString());
            }
            if (info.protocol != null) line.append(", ").append(info.protocol);
            if (info.cipherSuite != null) line.append(", ").append(info.cipherSuite);
            if (info.certIssuer != null) line.append(", vydal: ").append(shortName(info.certIssuer));
            if (info.protocol == null && info.cipherSuite == null && info.note != null) {
                line.append(" (TLS info: ").append(info.note).append(")");
            }
            tls.setText(name + "\n" + info.toDetailText());
        }
        catch (Throwable e) {
            line.append("CHYBA po ").append(now() - t0).append(" ms - ").append(e.toString());
        }
        finally {
            setPhase("Zavírání spojení");
            try { req.close(); } catch (Throwable e) {}
        }
        if (lines.length() > 0) lines.append('\n');
        lines.append(line);
        results.setText(lines.toString());
    }

//#ifdef JAVA_TLS
    /**
     * Java TLS: new connection (TCP + handshake timed separately), GET, then a second GET
     * over the same connection when the server keeps it open - shows what keep-alive saves.
     */
    private void testJava(String url, String name) {
        String[] hp = JavaTls.splitUrl(url);
        java.util.Vector headers = new java.util.Vector();
        headers.addElement(new String[] {"User-Agent", "Nokia9300 pubtran-j2me"});
        headers.addElement(new String[] {"Accept", "*/*"});
        StringBuffer line = new StringBuffer(name).append(" [Java]: ");
        long t0 = System.currentTimeMillis();
        JavaTls c = null;
        try {
            c = JavaTls.connect(hp[0], Integer.parseInt(hp[1]));
            line.append("TCP ").append(c.connectMs).append(" ms, TLS ").append(c.handshakeMs).append(" ms");
            if (c.client.resumed) line.append(" (obnoveno)");
            JavaTls.Response r = c.request("GET", hp[2], headers, null, null);
            line.append(", HTTP ").append(r.code).append(" za ").append(r.waitMs + r.downloadMs).append(" ms, ")
                .append(r.body.length).append(" B");
            TlsInfo info = c.client.info;
            if (info.protocol != null) line.append(", ").append(info.protocol);
            if (info.cipherSuite != null) line.append(", ").append(info.cipherSuite);
            if (info.certIssuer != null) line.append(", vydal: ").append(shortName(info.certIssuer));
            tls.setText(name + " (Java TLS)\n" + info.toDetailText());
            if (c.isOpen()) {
                JavaTls.Response r2 = c.request("GET", hp[2], headers, null, null);
                line.append("; 2. požadavek stejným spojením: HTTP ").append(r2.code)
                    .append(" za ").append(r2.waitMs + r2.downloadMs).append(" ms");
            }
            else {
                line.append("; server spojení zavřel (bez keep-alive)");
            }
        }
        catch (Throwable e) {
            line.append("CHYBA po ").append(System.currentTimeMillis() - t0).append(" ms - ").append(e.toString());
        }
        finally {
            if (c != null) c.close();
        }
        if (lines.length() > 0) lines.append('\n');
        lines.append(line);
        results.setText(lines.toString());
    }

//#endif

    /** "CN=R11, O=Let's Encrypt, C=US" -> "R11 / Let's Encrypt" (best effort). */
    private static String shortName(String dn) {
        String cn = field(dn, "CN="), o = field(dn, "O=");
        if (cn == null && o == null) return dn;
        if (cn == null) return o;
        if (o == null) return cn;
        return cn + " / " + o;
    }

    private static String field(String dn, String key) {
        int i = dn.indexOf(key);
        while (i > 0 && dn.charAt(i - 1) != ',' && dn.charAt(i - 1) != ' ' && dn.charAt(i - 1) != ';') {
            i = dn.indexOf(key, i + 1);
        }
        if (i < 0) return null;
        int start = i + key.length();
        int end = dn.indexOf(',', start);
        int end2 = dn.indexOf(';', start);
        if (end < 0 || (end2 >= 0 && end2 < end)) end = end2;
        return (end < 0 ? dn.substring(start) : dn.substring(start, end)).trim();
    }

    public void commandAction(Command c, Item item) {
        if (c == RUN_ONE) start(new String[] {host.getString()});
        else if (c == RUN_ALL) start(LIGHT);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == HIDE_COMMAND) {
            App.disp.setCurrent(this);
            return;
        }
        if (c == BACK_COMMAND) App.disp.setCurrent(back);
    }
}
