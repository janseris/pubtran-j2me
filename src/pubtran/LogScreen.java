package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Browsable list of recent PubtranApi requests (see RequestLog) - "The logs will be browsable in
 * the app". Selecting one drills into its full TLS/certificate detail via
 * LogDetailScreen. List-based rather than Canvas like
 * StartScreen - this is a plain data browser, no custom graphics needed.
 */
public class LogScreen extends List implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);
    private static final Command CLEAR_COMMAND = new Command("Smazat log", Command.SCREEN, 1);
    private static final Command SEND_COMMAND = new Command("Odeslat log na PC", Command.SCREEN, 2);

    private final Displayable back;
    private Vector entries;

    public LogScreen(Displayable back) {
        super("Log požadavků", List.IMPLICIT);
        this.back = back;
        addCommand(BACK_COMMAND);
        addCommand(CLEAR_COMMAND);
        addCommand(SEND_COMMAND);
        setCommandListener(this);
        reload();
    }

    private void reload() {
        deleteAll();
        entries = RequestLog.getEntries();
        if (entries.isEmpty()) {
            append("(zatím žádné požadavky - nejdřív něco vyhledejte)", null);
        }
        else {
            for (int i = 0; i < entries.size(); i++) {
                LogEntry e = (LogEntry) entries.elementAt(i);
                append(e.summaryLine(), null);
            }
        }
    }

    private void openSelected() {
        if (entries.isEmpty()) return;
        int idx = getSelectedIndex();
        if (idx < 0 || idx >= entries.size()) return;
        LogEntry e = (LogEntry) entries.elementAt(idx);
        App.disp.setCurrent(new LogDetailScreen(e, this));
    }

    public void commandAction(Command c, Displayable d) {
        if (c == BACK_COMMAND) {
            App.disp.setCurrent(back);
        }
        else if (c == CLEAR_COMMAND) {
            RequestLog.clear();
            reload();
        }
        else if (c == SEND_COMMAND) {
            setTitle("Odesílání na PC...");
            new Thread() {
                public void run() {
                    // the persisted log: also has requests from before an app or phone freeze
                    StringBuffer sb = new StringBuffer("=== Log požadavků, ")
                        .append(System.getProperty("microedition.platform")).append(", ")
                        .append(new java.util.Date().toString()).append(" ===\n");
                    sb.append(RequestLog.loadPersisted());
                    setTitle(PcUpload.post("requestlog", sb.toString()));
                }
            }.start();
        }
        else if (c == List.SELECT_COMMAND) {
            openSelected();
        }
    }
}
