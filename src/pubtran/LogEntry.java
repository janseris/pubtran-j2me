package pubtran;

/**
 * One row in RequestLog: everything known about a single PubtranApi request - full
 * URL, HTTP request/response (status line + headers), sizes, timing, and the
 * TLS/certificate details the native TLS stack (SSLADAPTOR.dll) negotiated. Filled in
 * by PubtranApi while the request runs. See LogScreen/LogDetailScreen.
 * (TLS handshake timing has its own test screen: TlsTestScreen.)
 */
public class LogEntry {
    public long timestamp;
    public String method;
    /** API method name, e.g. "getroutesopt". */
    public String path;
    public String url;
    /** Which transport carried it. */
    public String tlsLabel;

    public boolean success;
    /** Set when success is false - the HTTP status or exception text. */
    public String errorMessage;
    public int requestBytes;
    public int responseBytes;

    // --- timing (ms, -1 = unknown / not applicable) ---
    public long durationMs;
    /** Until the response status arrived: connect + TLS + sending + server time. */
    public long waitMs = -1;
    /** Reading the response headers + body. */
    public long downloadMs = -1;

    // --- Java TLS path only (JavaTls) ---
    /** The request went over an already open connection (no TCP connect, no handshake). */
    public boolean reusedConnection;
    /** TCP connect (socket://) and the TLS handshake of a new connection, or -1. */
    public long connectMs = -1;
    public long handshakeMs = -1;
    /** The handshake resumed the previous TLS session (abbreviated handshake). */
    public boolean resumedSession;

    // --- HTTP ---
    public String statusLine;
    public String requestHeaders;
    public String responseHeaders;

    /** May itself just hold a note if nothing could be captured. */
    public TlsInfo tlsInfo;

    public String summaryLine() {
        StringBuffer sb = new StringBuffer(path).append(" - ");
        if (!success) sb.append("CHYBA po ");
        sb.append(durationMs).append(" ms");
        if (success) sb.append(", ").append(PubtranApi.formatKB(responseBytes));
        if (handshakeMs >= 0) sb.append(", TLS ").append(handshakeMs).append(" ms");
        else if (reusedConnection) sb.append(", spoj. znovu");
        return sb.toString();
    }
}
