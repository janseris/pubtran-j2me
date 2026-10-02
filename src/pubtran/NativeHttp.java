package pubtran;

import fi.gtrxac.bluewap.http.StandardHTTP;
import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.HttpsConnection;

/**
 * The app's transport: StandardHTTP over HttpConnection (https://) - the phone's
 * native TLS stack (SSLADAPTOR.dll), no pure-Java TLS. HttpConnection hides the TLS
 * handshake inside getResponseCode(), so the log shows "connect + TLS + server wait"
 * as one number; the handshake itself is measured separately in TlsTestScreen.
 */
public class NativeHttp extends StandardHTTP {
    public static final String LABEL = "HttpConnection https:// (nativní TLS, SSLADAPTOR.dll)";

    private Object connection;

    public NativeHttp(String method, String url) {
        super(method, url);
    }

    protected Object openConnection(String url) throws IOException {
        connection = Connector.open(url);
        return connection;
    }

    /**
     * Aborts the request from another thread (cancel / stall watchdog): closes the
     * connection and its streams, so a read or getResponseCode() blocked in it fails.
     */
    public void abort() {
        try { close(); } catch (Throwable e) {}
        Object c = connection;
        if (c instanceof javax.microedition.io.Connection) {
            try { ((javax.microedition.io.Connection) c).close(); } catch (Throwable e) {}
        }
    }

    /** Status line + response headers into e. Call after getResponseCode(), before reading the body. */
    public void fillResponseInfo(LogEntry e) {
        if (!(connection instanceof HttpConnection)) return;
        HttpConnection hc = (HttpConnection) connection;
        try {
            e.statusLine = "HTTP " + hc.getResponseCode() + " " + hc.getResponseMessage();
            StringBuffer sb = new StringBuffer();
            for (int i = 0; ; i++) {
                String k = hc.getHeaderFieldKey(i);
                if (k == null) break;
                sb.append(k).append(": ").append(hc.getHeaderField(i)).append('\n');
            }
            e.responseHeaders = sb.toString().trim();
        }
        catch (Exception ex) {
            e.responseHeaders = "(nelze přečíst: " + ex + ")";
        }
    }

    /** Never throws - returns a TlsInfo with a note when nothing could be read. */
    public TlsInfo captureTlsInfo() {
        if (!(connection instanceof HttpsConnection)) {
            TlsInfo info = new TlsInfo();
            info.note = connection == null
                ? "Spojení nebylo otevřeno."
                : "Není HTTPS spojení - TLS nebylo použito.";
            return info;
        }
        try {
            return TlsInfo.from(((HttpsConnection) connection).getSecurityInfo());
        }
        catch (Exception e) {
            TlsInfo info = new TlsInfo();
            info.note = "TLS info nelze přečíst: " + e;
            return info;
        }
    }
}
