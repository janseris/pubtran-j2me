package pubtran;


import java.util.Vector;

/**
 * Bounded in-memory log of PubtranApi requests - "The logs will be browsable in the app",
 * per request, showing how long it took and what TLS/certificate info could be
 * captured. Newest entries first; oldest entries drop off past MAX_ENTRIES so this
 * can't grow without bound over a long session. Not persisted - it's a live debugging
 * aid for this run, not a saved history.
 */
public class RequestLog {
    private static final int MAX_ENTRIES = 50;
    private static Vector entries = new Vector();

    public static synchronized void add(LogEntry entry) {
        entries.insertElementAt(entry, 0);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeElementAt(entries.size() - 1);
        }
    }

    public static synchronized Vector getEntries() {
        return entries;
    }

    public static synchronized void clear() {
        entries.removeAllElements();
    }
}
