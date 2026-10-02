package pubtran;


/**
 * The search form's state (Odkud / Kam / Přes / Kdy + filters) - one shared instance,
 * SearchState.current, edited by the start screen and its sub-screens.
 */
public class SearchState {
    public static SearchState current = new SearchState();

    public Place from;
    public Place to;
    public Place via;

    /** Wall-clock search time; useNow = take the current time when searching. */
    public boolean useNow = true;
    public int year, month, day, hour, minute;

    public boolean isDeparture = true;
    public boolean onlyDirect;
    public boolean lowFloor;

    public boolean train = true, bus = true, tram = true, trolley = true, metro = true, cable = true, ferry = true;

    /** Results per page - SearchOptions.DOWNLOAD_LIMIT in the app. */
    public static final int COUNT = 5;

    public void setTime(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(new java.util.Date(millis));
        year = c.get(java.util.Calendar.YEAR);
        month = c.get(java.util.Calendar.MONTH) + 1;
        day = c.get(java.util.Calendar.DAY_OF_MONTH);
        hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        minute = c.get(java.util.Calendar.MINUTE);
    }

    /** Local time as millis (for a DateField). */
    public long getTimeMillis() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.YEAR, year);
        c.set(java.util.Calendar.MONTH, month - 1);
        c.set(java.util.Calendar.DAY_OF_MONTH, day);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTime().getTime();
    }

    /** "2026-09-30T20:56:00" - the backend's "when" (local time, no offset). */
    public String whenString() {
        if (useNow) setTime(System.currentTimeMillis());
        return year + "-" + Fmt.pad2(month) + "-" + Fmt.pad2(day) + "T"
            + Fmt.pad2(hour) + ":" + Fmt.pad2(minute) + ":00";
    }

    public String whenLabel() {
        String t = useNow ? "Teď" : (day + "." + month + ". " + hour + ":" + Fmt.pad2(minute));
        return (isDeparture ? "Odjezd " : "Příjezd ") + t;
    }

    public void swap() {
        Place t = from;
        from = to;
        to = t;
    }
}
