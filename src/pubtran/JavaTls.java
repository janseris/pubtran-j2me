//#ifdef JAVA_TLS
package pubtran;

import fi.gtrxac.bluewap.http.HTTP;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;
import org.bouncycastle.crypto.tls.TlsClientProtocol;

/**
 * Pure-Java HTTPS (BouncyCastle TLS over socket://) for hosts the phone's native TLS
 * can't reach - see the EKA1 patch issue: Java's HttpsConnection sends no SNI and some
 * servers (pubtran-backend.mapy.cz) stall the phone.
 *
 * The handshake runs in Java and is slow on the Nokia 9300, so the connection is kept
 * open (HTTP/1.1 keep-alive) and reused for the following requests: only the first
 * request of a session pays for the handshake. If the server has meanwhile closed the
 * idle connection, the request is sent again over a new one (all API calls here are
 * read-only searches, so repeating one is harmless).
 *
 * One instance = one TCP + TLS connection. exchange() manages the shared one.
 */
public class JavaTls {
    public static final String LABEL = "Java TLS (BouncyCastle) přes socket://";

    // ---------------- socket opening (replaceable for PC tests) ----------------

    /** A plain TCP connection. */
    public static class RawSocket {
        public InputStream in;
        public OutputStream out;
        public Object handle;

        public void close() {
            try { in.close(); } catch (Throwable e) {}
            try { out.close(); } catch (Throwable e) {}
            try { if (handle instanceof SocketConnection) ((SocketConnection) handle).close(); } catch (Throwable e) {}
        }
    }

    public interface Opener {
        RawSocket open(String host, int port) throws IOException;
    }

    /** null = Connector.open("socket://host:port"). Tests on a PC replace it. */
    public static Opener opener;

    private static RawSocket openSocket(String host, int port) throws IOException {
        if (opener != null) return opener.open(host, port);
        SocketConnection sc;
        try {
            sc = (SocketConnection) Connector.open("socket://" + host + ":" + port);
        }
        catch (SecurityException e) {
            throw new SecurityException("socket:// není povolen - nepodepsaná aplikace? "
                + "Java TLS potřebuje podepsanou verzi (instalace přes .jad + důvěryhodný certifikát). " + e);
        }
        RawSocket r = new RawSocket();
        r.handle = sc;
        r.in = sc.openInputStream();
        r.out = sc.openOutputStream();
        return r;
    }

    // ---------------- live phase for the loading overlay ----------------

    /** What the request in flight is doing ("TLS handshake (Java)"), null when idle. */
    public static volatile String phase;
    public static volatile long phaseStart;

    private static void setPhase(String p) {
        phase = p;
        phaseStart = System.currentTimeMillis();
    }

    // ---------------- one connection ----------------

    public final String host;
    public final int port;
    private RawSocket raw;
    private TlsClientProtocol protocol;
    private InputStream in;
    private OutputStream out;
    private final byte[] buf = new byte[2048];
    private int bufPos, bufLen;

    public JavaTlsClient client;
    public long connectMs, handshakeMs;
    /** Requests already completed over this connection. */
    public int requests;
    private boolean closed;

    private JavaTls(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Opens TCP + does the TLS handshake. */
    public static JavaTls connect(String host, int port) throws IOException {
        JavaTls c = new JavaTls(host, port);
        long t0 = System.currentTimeMillis();
        setPhase("Připojování (TCP)");
        c.raw = openSocket(host, port);
        long t1 = System.currentTimeMillis();
        c.connectMs = t1 - t0;
        setPhase("TLS handshake (Java)");
        try {
            c.protocol = new TlsClientProtocol(c.raw.in, c.raw.out, new java.security.SecureRandom());
            c.client = new JavaTlsClient(host);
            try {
                c.protocol.connect(c.client);
            }
            catch (IOException e) {
                JavaTlsClient.forgetSession(host); // in case the resumption was the problem
                throw e;
            }
        }
        catch (IOException e) {
            c.raw.close();
            throw e;
        }
        catch (RuntimeException e) {
            c.raw.close();
            throw e;
        }
        c.handshakeMs = System.currentTimeMillis() - t1;
        c.in = c.protocol.getInputStream();
        c.out = c.protocol.getOutputStream();
        return c;
    }

    public boolean isOpen() {
        return !closed;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { if (protocol != null) protocol.close(); } catch (Throwable e) {}
        if (raw != null) raw.close();
    }

    // ---------------- HTTP/1.1 over the connection ----------------

    public static class Response {
        public int code;
        public String statusLine;
        public String headers;
        public byte[] body;
        public boolean keepAlive;
        /** Sending the request until the status line arrived. */
        public long waitMs;
        public long downloadMs;
        /** Connection info: was it reused, how long did it take to set up. */
        public boolean reused;
        public long connectMs, handshakeMs;
        public boolean resumedSession;
        public TlsInfo tls;
        /** "Request text" (request line + headers) as sent, for the log. */
        public String requestText;

        public String header(String name) {
            if (headers == null) return null;
            String lower = headers.toLowerCase();
            String key = name.toLowerCase() + ": ";
            int i = lower.startsWith(key) ? 0 : lower.indexOf("\n" + key);
            if (i < 0) return null;
            if (i > 0) i++;
            int end = headers.indexOf('\n', i);
            return headers.substring(i + key.length(), end < 0 ? headers.length() : end).trim();
        }
    }

    /** Thrown when the connection failed before any part of the response arrived. */
    static class NoResponseException extends IOException {
        NoResponseException(String m) {
            super(m);
        }
    }

    /**
     * One request over this connection. headers: Vector of String[2]. Never sends
     * "Connection: close" - the caller decides afterwards whether to keep it.
     */
    public Response request(String method, String path, Vector headers, byte[] body,
                            HTTP.ProgressListener progress) throws IOException {
        StringBuffer rq = new StringBuffer();
        rq.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        rq.append("Host: ").append(host).append("\r\n");
        for (int i = 0; headers != null && i < headers.size(); i++) {
            String[] h = (String[]) headers.elementAt(i);
            rq.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        if (body != null) rq.append("Content-Length: ").append(body.length).append("\r\n");
        rq.append("Connection: keep-alive\r\n\r\n");

        String text = rq.toString();
        byte[] head = ascii(text);
        byte[] all = new byte[head.length + (body == null ? 0 : body.length)];
        System.arraycopy(head, 0, all, 0, head.length);
        if (body != null) System.arraycopy(body, 0, all, head.length, body.length);

        Response r = new Response();
        r.requestText = text.trim();
        setPhase("Odesílání");
        long t0 = System.currentTimeMillis();
        String status;
        try {
            out.write(all); // one TLS record
            out.flush();
            setPhase("Čekání na server");
            status = readLine();
        }
        catch (IOException e) {
            close();
            throw new NoResponseException(e.toString());
        }
        if (status == null) {
            close();
            throw new NoResponseException("spojení zavřeno serverem");
        }
        r.waitMs = System.currentTimeMillis() - t0;
        r.statusLine = status;
        r.code = PubtranApi.statusCode(status);
        setPhase(null);

        long t1 = System.currentTimeMillis();
        try {
            StringBuffer hs = new StringBuffer();
            for (;;) {
                String line = readLine();
                if (line == null) throw new EOFException("konec spojení v hlavičkách");
                if (line.length() == 0) break;
                hs.append(line).append('\n');
            }
            r.headers = hs.toString().trim();

            String conn = r.header("Connection");
            boolean http10 = status.startsWith("HTTP/1.0");
            r.keepAlive = !http10 && (conn == null || conn.toLowerCase().indexOf("close") < 0);

            String te = r.header("Transfer-Encoding");
            String cl = r.header("Content-Length");
            if ("HEAD".equals(method) || r.code == 204 || r.code == 304 || (r.code >= 100 && r.code < 200)) {
                r.body = new byte[0];
            }
            else if (te != null && te.toLowerCase().indexOf("chunked") >= 0) {
                r.body = readChunked(progress);
            }
            else if (cl != null) {
                r.body = readFully(Integer.parseInt(cl.trim()), progress);
            }
            else {
                r.body = readToEnd(progress);
                r.keepAlive = false;
            }
        }
        catch (IOException e) {
            close();
            throw e;
        }
        catch (RuntimeException e) {
            close();
            throw new IOException("Chybná HTTP odpověď: " + e);
        }
        r.downloadMs = System.currentTimeMillis() - t1;
        requests++;
        if (!r.keepAlive) close();
        return r;
    }

    // ---------------- shared keep-alive connection ----------------

    private static JavaTls shared;

    /**
     * Sends a request over the shared connection to url's host, opening it first if
     * needed. A reused connection that turns out dead (the server closed it while idle)
     * is replaced and the request sent once more.
     */
    public static synchronized Response exchange(String method, String url, Vector headers, byte[] body,
                                                 HTTP.ProgressListener progress) throws IOException {
        String[] hp = splitUrl(url);
        String host = hp[0];
        int port = Integer.parseInt(hp[1]);
        String path = hp[2];
        try {
            for (int attempt = 0; ; attempt++) {
                boolean reused = shared != null && shared.isOpen()
                    && shared.host.equals(host) && shared.port == port;
                if (!reused) {
                    if (shared != null) shared.close();
                    shared = null;
                    if (progress != null) progress.onConnecting();
                    shared = connect(host, port);
                }
                JavaTls c = shared;
                try {
                    Response r = c.request(method, path, headers, body, progress);
                    r.reused = reused;
                    r.connectMs = reused ? 0 : c.connectMs;
                    r.handshakeMs = reused ? 0 : c.handshakeMs;
                    r.resumedSession = c.client.resumed;
                    r.tls = c.client.info;
                    return r;
                }
                catch (NoResponseException e) {
                    shared = null;
                    if (!reused || attempt > 0) throw e;
                    // dead idle connection - one more try over a new one
                }
            }
        }
        finally {
            setPhase(null);
        }
    }

    /** Closes the shared connection (e.g. when the app exits). */
    public static synchronized void closeShared() {
        if (shared != null) shared.close();
        shared = null;
    }

    /** Is a shared connection to this URL's host open? */
    public static synchronized boolean hasShared(String url) {
        try {
            String[] hp = splitUrl(url);
            return shared != null && shared.isOpen() && shared.host.equals(hp[0]);
        }
        catch (Throwable e) {
            return false;
        }
    }

    /** "https://host[:port]/path" -> {host, port, path}. */
    static String[] splitUrl(String url) {
        String u = url;
        if (u.startsWith("https://")) u = u.substring(8);
        int slash = u.indexOf('/');
        String hostPort = slash < 0 ? u : u.substring(0, slash);
        String path = slash < 0 ? "/" : u.substring(slash);
        String port = "443";
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            port = hostPort.substring(colon + 1);
            hostPort = hostPort.substring(0, colon);
        }
        return new String[] {hostPort, port, path};
    }

    // ---------------- low-level reading ----------------

    private int fill() throws IOException {
        if (bufPos < bufLen) return bufLen - bufPos;
        int n;
        try {
            n = in.read(buf, 0, buf.length);
        }
        catch (EOFException e) { // incl. TlsNoCloseNotifyException: TCP closed without close_notify
            n = -1;
        }
        if (n <= 0) return -1;
        bufPos = 0;
        bufLen = n;
        return n;
    }

    /** One header line without CRLF, or null at end of stream. */
    private String readLine() throws IOException {
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        for (;;) {
            if (fill() < 0) return any ? sb.toString() : null;
            any = true;
            int b = buf[bufPos++] & 0xFF;
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("Příliš dlouhý řádek hlavičky");
        }
        return sb.toString();
    }

    private int readInto(byte[] dst, int off, int len) throws IOException {
        if (fill() < 0) return -1;
        int n = Math.min(len, bufLen - bufPos);
        System.arraycopy(buf, bufPos, dst, off, n);
        bufPos += n;
        return n;
    }

    private byte[] readFully(int length, HTTP.ProgressListener progress) throws IOException {
        byte[] data = new byte[length];
        int got = 0;
        if (progress != null) progress.onProgress(0, length);
        while (got < length) {
            int n = readInto(data, got, length - got);
            if (n < 0) throw new EOFException("Odpověď useknutá po " + got + " z " + length + " B");
            got += n;
            if (progress != null) progress.onProgress(got, length);
        }
        return data;
    }

    private byte[] readChunked(HTTP.ProgressListener progress) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        for (;;) {
            String line = readLine();
            if (line == null) throw new EOFException("Konec spojení v chunked odpovědi");
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            line = line.trim();
            if (line.length() == 0) continue;
            int size = Integer.parseInt(line, 16);
            if (size == 0) {
                // trailer headers until the empty line
                for (;;) {
                    String t = readLine();
                    if (t == null || t.length() == 0) break;
                }
                return bo.toByteArray();
            }
            while (size > 0) {
                int n = readInto(tmp, 0, Math.min(size, tmp.length));
                if (n < 0) throw new EOFException("Konec spojení uvnitř chunku");
                bo.write(tmp, 0, n);
                size -= n;
                if (progress != null) progress.onProgress(bo.size(), -1);
            }
            readLine(); // CRLF after the chunk
        }
    }

    private byte[] readToEnd(HTTP.ProgressListener progress) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        for (;;) {
            int n = readInto(tmp, 0, tmp.length);
            if (n < 0) break;
            bo.write(tmp, 0, n);
            if (progress != null) progress.onProgress(bo.size(), -1);
        }
        return bo.toByteArray();
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }
}
//#endif
