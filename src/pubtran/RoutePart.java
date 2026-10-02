package pubtran;


import java.util.Vector;

/**
 * One part of a connection: riding a vehicle, walking, or waiting for a transfer
 * (getroutesopt route item type 0 / 1 / 2). A ride returned by getnextdepartures
 * (another run of the same line) is also a RoutePart of kind RIDE.
 */
public class RoutePart {
    public static final int RIDE = 0, WALK = 1, WAIT = 2;

    public int kind;
    public String departureIso = "";
    public String arrivalIso = "";

    // --- WALK ---
    public int distance;

    // --- RIDE ---
    /** "line;;startId;;depISO;;endId;;arrISO;;startIdx;;endIdx" - the tripId used by every other call. */
    public String partDescription = "";
    public String routeName = "";
    public String routeLongName = "";
    public int vehicleType;
    public String agency = "";
    public int startIndex;
    public int endIndex;
    public String startPlatform;
    public String endPlatform;
    /** The WHOLE trip, TripStop each; startIndex..endIndex is the ridden part. */
    public Vector stops = new Vector();
    /** Info from the search itself. */
    public Vector info = new Vector();
    /** Info refreshed by gettripinfos (realtime), or null. */
    public Vector liveInfo;

    /** item = route item / trip struct; stops, agencies = Vectors of FrpcStruct. */
    static RoutePart rideFrom(FrpcStruct item, Vector stopsTable, Vector agencies) {
        RoutePart p = new RoutePart();
        p.kind = RIDE;
        p.departureIso = item.getString("departureISO", "");
        p.arrivalIso = item.getString("arrivalISO", "");
        p.partDescription = item.getString("partDescription", "");
        p.routeName = item.getString("routeName", "");
        p.routeLongName = item.getString("routeLongName", "");
        p.vehicleType = item.getInt("vehicleType", 0);
        p.startIndex = item.getInt("startStopTripIndex", 0);
        p.endIndex = item.getInt("endStopTripIndex", 0);
        p.startPlatform = item.getString("startPlatform", null);
        p.endPlatform = item.getString("endPlatform", null);

        int ag = item.getInt("agencyIndex", -1);
        if (ag >= 0 && ag < agencies.size()) {
            FrpcStruct a = FrpcStruct.structAt(agencies, ag);
            if (a != null) {
                String sn = a.getString("short_name", "");
                p.agency = sn.length() > 0 ? sn : a.getString("name", "");
            }
        }

        Vector td = item.getArray("tripData");
        for (int i = 0; i < td.size(); i++) {
            FrpcStruct t = FrpcStruct.structAt(td, i);
            if (t != null) p.stops.addElement(TripStop.from(t, stopsTable));
        }

        Vector inf = item.getArray("info");
        for (int i = 0; i < inf.size(); i++) {
            FrpcStruct s = FrpcStruct.structAt(inf, i);
            if (s != null) p.info.addElement(Info.from(s));
        }
        return p;
    }

    public TripStop boarding() {
        return startIndex < stops.size() ? (TripStop) stops.elementAt(startIndex) : null;
    }

    public TripStop alighting() {
        return endIndex < stops.size() ? (TripStop) stops.elementAt(endIndex) : null;
    }

    /** Live info first (if any), then the search's own info. */
    private Info findInfo(int id, long stopId) {
        Vector[] lists = { liveInfo, info };
        for (int l = 0; l < 2; l++) {
            Vector v = lists[l];
            if (v == null) continue;
            for (int i = 0; i < v.size(); i++) {
                Info x = (Info) v.elementAt(i);
                if (x.id == id && (stopId < 0 || x.stopId == stopId)) return x;
            }
        }
        return null;
    }

    public boolean hasInfo(int id) {
        return findInfo(id, -1) != null;
    }

    /** Current delay (info 302/300) or Integer.MIN_VALUE when there's no realtime data. */
    public int delaySeconds() {
        Info d = findInfo(Info.DELAY, -1);
        if (d == null || !d.hasDelay) d = findInfo(Info.NO_DELAY, -1);
        return (d != null && d.hasDelay) ? d.delaySeconds : Integer.MIN_VALUE;
    }

    /** Statistical delay at a stop (info 500), or Integer.MIN_VALUE. */
    public int usualDelayAt(long stopId) {
        Info d = findInfo(Info.USUAL_DELAY, stopId);
        return (d != null && d.hasDelay) ? d.delaySeconds : Integer.MIN_VALUE;
    }

    public String platformAt(long stopId) {
        Info d = findInfo(Info.STOP_PLATFORM, stopId);
        return d != null ? d.text.trim() : null;
    }

    /** Warnings and realtime messages, de-duplicated by text. */
    public Vector warnings() {
        Vector out = new Vector();
        Vector seen = new Vector();
        Vector[] lists = { liveInfo, info };
        for (int l = 0; l < 2; l++) {
            Vector v = lists[l];
            if (v == null) continue;
            for (int i = 0; i < v.size(); i++) {
                Info x = (Info) v.elementAt(i);
                if (!(x.isWarning() || x.id == Info.DELAY || x.id == Info.NO_DELAY)) continue;
                if (x.text == null || x.text.length() == 0 || seen.contains(x.text)) continue;
                seen.addElement(x.text);
                out.addElement(x);
            }
        }
        return out;
    }

    /** e.g. "Bus 780400" or "Vlak R12 / R 914". */
    public String title() {
        String line = (vehicleType == 7 || routeLongName.length() > routeName.length()) ? routeLongName : routeName;
        return Fmt.vehicleName(vehicleType) + " " + line;
    }
}
