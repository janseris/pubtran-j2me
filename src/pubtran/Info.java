package pubtran;


/**
 * One entry of a ride's "info" array (or a stop's "stCodes"). The numeric ids come
 * from the Android app's own constants (JourneyPart.INFO_*, RouteItem.FLAG_*) - see
 * PubtranClient/API.md for the full table.
 */
public class Info {
    // ride info ids
    public static final int MANDATORY_RESERVATION = 14;
    public static final int ACCESSIBLE = 15;
    public static final int BICYCLE = 20;
    public static final int PLATFORM = 42;
    public static final int NEEDS_BOOKING = 43;
    public static final int WIFI = 45;
    public static final int AIR_CONDITIONING = 46;
    public static final int NO_DELAY = 300;        // realtime, delay seconds (may be negative)
    public static final int DELAY = 302;           // realtime, delay seconds + text
    public static final int STOP_PLATFORM = 400;   // stopID + "arr / dep" track
    public static final int USUAL_DELAY = 500;     // statistical delay at stopID, seconds

    // stop code ids
    public static final int STOP_ZONE = 7;
    public static final int STOP_ACCESSIBLE = 24;
    public static final int STOP_REQUEST = 28;
    public static final int STOP_ONLY_GET_OFF = 37;
    public static final int STOP_ONLY_GET_ON = 38;

    public int id;
    public String text = "";
    public String url;
    public boolean hasDelay;
    public int delaySeconds;
    public long stopId = -1;

    static Info from(FrpcStruct s) {
        Info i = new Info();
        i.id = s.getInt("id", 0);
        i.text = s.getString("text", "");
        i.url = s.getString("url", null);
        if (s.has("delay")) {
            i.hasDelay = true;
            i.delaySeconds = s.getInt("delay", 0);
        }
        i.stopId = s.getLong("stopID", -1);
        return i;
    }

    /** Disruption/warning ids (JourneyPart.INFO_WARNINGS in the app). */
    public boolean isWarning() {
        return id == 14 || (id >= 101 && id <= 108) || (id >= 201 && id <= 205);
    }
}
