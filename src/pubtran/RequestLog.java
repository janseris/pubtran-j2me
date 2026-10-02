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
        persist(entry.fullText());
    }

    // ---- persisted text log (survives an app or phone freeze) ----

    private static final String STORE = "pt_log";
    private static final int MAX_PERSIST = 24000;

    /** Appends text to the log kept in RMS (oldest text dropped past MAX_PERSIST chars). */
    public static synchronized void persist(String text) {
        String all = loadPersisted() + text + "\n";
        if (all.length() > MAX_PERSIST) all = all.substring(all.length() - MAX_PERSIST);
        byte[] data = Frpc.utf8Encode(all);
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, data, 0, data.length);
            else rs.addRecord(data, 0, data.length);
        }
        catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    /** The persisted log text, including entries from earlier app runs. */
    public static synchronized String loadPersisted() {
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null) return Frpc.utf8Decode(b, 0, b.length);
            }
        }
        catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
        return "";
    }

    public static synchronized void clearPersisted() {
        try { javax.microedition.rms.RecordStore.deleteRecordStore(STORE); } catch (Exception e) {}
    }

    public static synchronized Vector getEntries() {
        return entries;
    }

    public static synchronized void clear() {
        entries.removeAllElements();
        clearPersisted();
    }
}
