package pubtran;


import java.util.Hashtable;
import java.util.Vector;

/** One stop of a ride's trip, with its times (from tripData + the stops table). */
public class TripStop {
    public long stopId;
    public String name = "?";
    public String arrivalIso = "";
    public String departureIso = "";
    /** stCodes: Integer id -> String text. */
    public Hashtable codes = new Hashtable();

    /** td = one tripData entry; stops = Vector of FrpcStruct {id, name, coord}. */
    static TripStop from(FrpcStruct td, Vector stops) {
        TripStop s = new TripStop();
        int idx = td.getInt("stopIndex", -1);
        if (idx >= 0 && idx < stops.size()) {
            FrpcStruct st = FrpcStruct.structAt(stops, idx);
            if (st != null) {
                s.stopId = st.getLong("id", 0);
                s.name = st.getString("name", "?");
            }
        }
        s.arrivalIso = td.getString("arrivalISO", "");
        s.departureIso = td.getString("departureISO", "");
        Vector codes = td.getArray("stCodes");
        for (int i = 0; i < codes.size(); i++) {
            FrpcStruct c = FrpcStruct.structAt(codes, i);
            if (c != null) s.codes.put(new Integer(c.getInt("id", 0)), c.getString("text", ""));
        }
        return s;
    }

    public String code(int id) {
        return (String) codes.get(new Integer(id));
    }

    public boolean has(int id) {
        return codes.containsKey(new Integer(id));
    }

    public String platform() {
        String p = code(Info.STOP_PLATFORM);
        return p != null ? p : code(Info.PLATFORM);
    }
}
