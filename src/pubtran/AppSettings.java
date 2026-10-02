package pubtran;

import javax.microedition.rms.RecordStore;

/**
 * App settings kept in RMS (one record, one byte per flag).
 *
 * searchAsYouType: the place picker sends a suggest request when typing pauses
 * (on by default). Off: only Enter / "Hledat" searches - for slow or paid connections,
 * since every request on the 9300 is a new TLS connection (~1-2 s).
 */
public class AppSettings {
    private static final String STORE = "pt_settings";

    private static boolean loaded;
    private static boolean searchAsYouType = true;

    public static boolean searchAsYouType() {
        load();
        return searchAsYouType;
    }

    public static void setSearchAsYouType(boolean on) {
        load();
        searchAsYouType = on;
        save();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null && b.length > 0) searchAsYouType = b[0] != 0;
            }
        }
        catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private static void save() {
        byte[] data = {(byte) (searchAsYouType ? 1 : 0)};
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, data, 0, data.length);
            else rs.addRecord(data, 0, data.length);
        }
        catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
    }
}
