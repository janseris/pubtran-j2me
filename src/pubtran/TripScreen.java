package pubtran;

import com.gtrxac.discord.App;

import java.util.Hashtable;
import javax.microedition.lcdui.*;

/**
 * One ride's whole trip: every stop with arrival/departure, platform, zone and notes;
 * the ridden part is highlighted (bold boarding/alighting, "|" marks the stops in
 * between, stops outside it in a small font).
 *
 * "< Předchozí spoj" / "Následující spoj >" replace the app's swipe: they load other
 * runs of the same line between the same stops with getnextdepartures, reqindex being
 * relative to the ORIGINAL ride (-1, +1, +2, ...), just like the app. Loaded runs are
 * cached, and each one's realtime delays are fetched right after it (gettripinfos).
 */
public class TripScreen extends Form implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);
    private static final Command PREV_COMMAND = new Command("< Předchozí spoj", Command.SCREEN, 1);
    private static final Command NEXT_COMMAND = new Command("Následující spoj >", Command.SCREEN, 2);
    private static final Command REFRESH_COMMAND = new Command("Obnovit zpoždění", Command.SCREEN, 3);

    private final RoutePart original;
    private final SearchState modes;
    private final Displayable back;
    private final Hashtable cache = new Hashtable();
    private int offset = 0;
    private RoutePart current;

    public TripScreen(RoutePart original, SearchState modes, Displayable back) {
        super(original.title());
        this.original = original;
        this.modes = modes;
        this.back = back;
        this.current = original;

        addCommand(BACK_COMMAND);
        addCommand(PREV_COMMAND);
        addCommand(NEXT_COMMAND);
        addCommand(REFRESH_COMMAND);
        setCommandListener(this);

        render();
    }

    /**
     * Shows this screen. Delays come with the search result already; fresh ones only
     * on "Obnovit zpoždění" (every request is a full TLS handshake on the 9300).
     */
    public void open() {
        App.disp.setCurrent(this);
    }

    /** Shows run `target` (relative to the original), loading it if needed. */
    private void load(final int target, final boolean forceRefresh) {
        final RoutePart cached = target == 0 ? original : (RoutePart) cache.get(new Integer(target));
        if (cached != null && !forceRefresh) {
            offset = target;
            current = cached;
            render();
            return;
        }
        final TripScreen self = this;
        RequestCallback cb = new RequestCallback() {
            public Object request() throws Exception {
                RoutePart run = cached != null ? cached : PubtranApi.otherRun(original, target, modes);
                if (run == null) throw new Exception("Spoj nenalezen.");
                // Only an explicit refresh asks for realtime delays - one request per
                // action (the trip from getnextdepartures already carries its info).
                if (forceRefresh) {
                    java.util.Vector one = new java.util.Vector();
                    one.addElement(run);
                    PubtranApi.refreshLive(one);
                }
                return run;
            }
            public void onSuccess(Object result) {
                RoutePart run = (RoutePart) result;
                if (target != 0) cache.put(new Integer(target), run);
                offset = target;
                current = run;
                render();
                App.disp.setCurrent(self);
            }
        };
        new RequestThread(cb, this).start();
    }

    private synchronized void render() {
        deleteAll();
        RoutePart r = current;
        setTitle(r.title() + "  " + positionLabel());

        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font plain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        TripStop b = r.boarding();
        TripStop a = r.alighting();
        String delay = Fmt.delay(r.delaySeconds());
        StringItem head = new StringItem(null,
            r.title() + (r.agency.length() > 0 ? " (" + r.agency + ")" : "") + (delay.length() > 0 ? "  " + delay : "")
            + "\n" + (b != null ? b.name : "?") + " " + Fmt.timeWithDay(r.departureIso, original.departureIso)
            + "  ->  " + (a != null ? a.name : "?") + " " + Fmt.timeWithDay(r.arrivalIso, original.departureIso)
            + " (" + Fmt.minutesBetween(r.departureIso, r.arrivalIso) + " min)");
        head.setFont(bold);
        head.setLayout(Item.LAYOUT_NEWLINE_AFTER);
        append(head);

        int n = r.stops.size();
        for (int i = 0; i < n; i++) {
            TripStop s = (TripStop) r.stops.elementAt(i);
            boolean ridden = i >= r.startIndex && i <= r.endIndex;
            boolean endpoint = i == r.startIndex || i == r.endIndex;

            String arr = i == 0 ? "     " : Fmt.time(s.arrivalIso);
            String dep = i == n - 1 ? "     " : Fmt.time(s.departureIso);
            StringBuffer line = new StringBuffer();
            line.append(ridden ? (endpoint ? "> " : "| ") : "  ");
            line.append(arr).append(" ").append(dep).append("  ").append(s.name);

            String plat = Fmt.platform(r, s, i == r.startIndex, i == r.endIndex);
            if (plat != null) line.append("  [nást. ").append(plat).append("]");
            String zone = s.code(Info.STOP_ZONE);
            if (zone != null && zone.length() > 0) line.append("  pásmo ").append(zone);
            String notes = Fmt.stopNotes(s, r);
            if (notes.length() > 0) line.append("  (").append(notes).append(")");

            StringItem item = new StringItem(null, line.toString());
            item.setFont(endpoint ? bold : (ridden ? plain : small));
            item.setLayout(Item.LAYOUT_NEWLINE_AFTER);
            append(item);
        }

        StringBuffer extra = new StringBuffer();
        String am = Fmt.amenities(r);
        if (am.length() > 0) extra.append("Vybavení: ").append(am);
        java.util.Vector w = r.warnings();
        for (int i = 0; i < w.size(); i++) {
            Info x = (Info) w.elementAt(i);
            if (extra.length() > 0) extra.append('\n');
            extra.append("! ").append(x.text.trim());
            if (x.url != null) extra.append(" (").append(x.url).append(")");
        }
        if (extra.length() > 0) extra.append('\n');
        extra.append("tripId: ").append(r.partDescription);
        StringItem e = new StringItem(null, extra.toString());
        e.setFont(small);
        e.setLayout(Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
        append(e);
    }

    private String positionLabel() {
        if (offset == 0) return "(zvolený)";
        if (offset > 0) return "(" + offset + ". následující)";
        return "(" + (-offset) + ". předchozí)";
    }

    public void commandAction(Command c, Displayable d) {
        if (c == BACK_COMMAND) {
            App.disp.setCurrent(back);
        }
        else if (c == PREV_COMMAND) {
            load(offset - 1, false);
        }
        else if (c == NEXT_COMMAND) {
            load(offset + 1, false);
        }
        else if (c == REFRESH_COMMAND) {
            load(offset, true);
        }
    }
}
