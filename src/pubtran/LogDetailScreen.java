package pubtran;

import com.gtrxac.discord.App;

import java.util.Date;
import javax.microedition.lcdui.*;

/**
 * Full detail of one logged request:
 *  - request: full URL, transport, request line + headers, bytes sent
 *  - timing: total, time until the response status (connect + TLS + server), download
 *  - response: HTTP status line, response headers, bytes received, result/error
 *  - TLS: protocol, cipher suite (+ strength), server certificate subject, issuer,
 *    signature algorithm, type/version, serial number, validity
 */
public class LogDetailScreen extends Form implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);

    private final Displayable back;
    private final Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private final Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

    public LogDetailScreen(LogEntry e, Displayable back) {
        super(e.method + " " + e.path);
        this.back = back;

        section("Požadavek");
        field("Kdy:", new Date(e.timestamp).toString());
        field("URL:", e.url != null ? e.url : PubtranApi.BASE_URL + e.path);
        field("Transport:", e.tlsLabel);
        field("Odesláno:", PubtranApi.formatKB(e.requestBytes) + " (tělo FastRPC)");
        if (e.requestHeaders != null) block(e.requestHeaders);

        section("Časy");
        field("Celkem:", e.durationMs + " ms");
        if (e.reusedConnection) {
            field("Spojení:", "znovu použité (bez připojení a TLS handshaku)");
        }
        else if (e.handshakeMs >= 0) {
            field("Připojení (TCP):", e.connectMs + " ms");
            field("TLS handshake:", e.handshakeMs + " ms" + (e.resumedSession ? " (obnovená relace)" : " (plný)"));
        }
        if (e.waitMs >= 0) {
            field("Do odpovědi:", e.waitMs + " ms" + (e.handshakeMs >= 0 || e.reusedConnection
                ? " (odeslání + server)" : " (připojení + TLS + server)"));
        }
        if (e.downloadMs >= 0) field("Stahování:", e.downloadMs + " ms");

        section("Odpověď");
        field("Výsledek:", e.success ? "OK" : ("Chyba - " + e.errorMessage));
        field("Status:", e.statusLine != null ? e.statusLine : "(žádná odpověď)");
        field("Přijato:", PubtranApi.formatKB(e.responseBytes));
        if (e.responseHeaders != null && e.responseHeaders.length() > 0) block(e.responseHeaders);

        section("TLS a certifikát");
        block(e.tlsInfo == null ? "(nezachyceno)" : e.tlsInfo.toDetailText());

        addCommand(BACK_COMMAND);
        setCommandListener(this);
    }

    private void section(String title) {
        StringItem s = new StringItem(null, title);
        s.setFont(bold);
        s.setLayout(Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
        append(s);
    }

    private void field(String label, String value) {
        StringItem s = new StringItem(label, value);
        s.setLayout(Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
        append(s);
    }

    private void block(String text) {
        StringItem s = new StringItem(null, text);
        s.setFont(small);
        s.setLayout(Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
        append(s);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == BACK_COMMAND) {
            App.disp.setCurrent(back);
        }
    }
}
