package pubtran;

import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * Sends text (test results, the request log) to the PC running ota/ota_server.js:
 * POST http://<address>/results?name=..., saved there in ota/uploads/. Plain HTTP, so it
 * doesn't go through the TLS patch.
 */
public class PcUpload {
    /** The PC's address (host:port); set from the HTTPS test screen's field. */
    public static String address = "192.168.137.1:8000";

    /** Blocking - call from a background thread. Returns a short status for the screen. */
    public static String post(String name, String text) {
        HttpConnection hc = null;
        OutputStream os = null;
        try {
            String pc = address.trim();
            if (pc.startsWith("http://")) pc = pc.substring(7);
            if (pc.endsWith("/")) pc = pc.substring(0, pc.length() - 1);
            byte[] body = text.getBytes("UTF-8");
            hc = (HttpConnection) Connector.open("http://" + pc + "/results?name=" + name);
            hc.setRequestMethod(HttpConnection.POST);
            hc.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            hc.setRequestProperty("Content-Length", String.valueOf(body.length));
            os = hc.openOutputStream();
            os.write(body);
            os.close();
            os = null;
            int code = hc.getResponseCode();
            return code == 200 ? "Odesláno na PC." : "Odeslání na PC: HTTP " + code;
        }
        catch (Throwable e) {
            return "Odeslání na PC selhalo: " + e;
        }
        finally {
            try { if (os != null) os.close(); } catch (Throwable e) {}
            try { if (hc != null) hc.close(); } catch (Throwable e) {}
        }
    }
}
