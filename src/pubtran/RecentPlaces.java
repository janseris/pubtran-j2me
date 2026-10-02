package pubtran;


import java.util.Vector;
import javax.microedition.rms.RecordStore;

/**
 * Recently picked places (shown in the place picker before typing, like the app does -
 * the app keeps them locally too, they never come from the server). Stored as one RMS
 * record of tab-separated lines.
 */
public class RecentPlaces {
    private static final String STORE = "pt_recent";
    private static final int MAX = 12;

    public static Vector load() {
        Vector out = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null) {
                    String[] lines = Fmt.split(Frpc.utf8Decode(b, 0, b.length), '\n');
                    for (int i = 0; i < lines.length; i++) {
                        Place p = Place.deserialize(lines[i]);
                        if (p != null) out.addElement(p);
                    }
                }
            }
        }
        catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
        return out;
    }

    public static void add(Place p) {
        Vector list = load();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (p.sameAs((Place) list.elementAt(i))) list.removeElementAt(i);
        }
        list.insertElementAt(p, 0);
        while (list.size() > MAX) list.removeElementAt(list.size() - 1);

        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(((Place) list.elementAt(i)).serialize());
        }
        byte[] data = Frpc.utf8Encode(sb.toString());

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
