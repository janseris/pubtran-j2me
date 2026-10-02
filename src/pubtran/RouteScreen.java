package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Detail of one connection: every part (ride / walk / transfer wait) with boarding and
 * alighting stop, times, platforms, delays, warnings and ticket prices.
 *
 * "Všechny zastávky" toggles the intermediate stops (the app shows them when you tap
 * the connection again). Each ride gets a "Spoj N: ..." command that opens
 * TripScreen - the ride's whole trip, with previous/next runs of the same line
 * (the app's swipe).
 */
public class RouteScreen extends Form implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);
    private static final Command ALL_STOPS_COMMAND = new Command("Všechny zastávky", Command.SCREEN, 1);
    private static final Command ENDS_ONLY_COMMAND = new Command("Jen nástup a výstup", Command.SCREEN, 1);
    private static final Command REFRESH_COMMAND = new Command("Obnovit zpoždění", Command.SCREEN, 9);

    private final Route conn;
    private final SearchState search;
    private final Displayable back;
    private boolean allStops = false;

    /** One Command per ride, same order as rideList. */
    private final Vector rideCommands = new Vector();
    private final Vector rideList;

    public RouteScreen(Route conn, SearchState search, Displayable back) {
        super(Fmt.time(conn.departureIso) + " - " + Fmt.time(conn.arrivalIso));
        this.conn = conn;
        this.search = search;
        this.back = back;
        this.rideList = conn.rides();

        addCommand(BACK_COMMAND);
        addCommand(ALL_STOPS_COMMAND);
        for (int i = 0; i < rideList.size(); i++) {
            RoutePart r = (RoutePart) rideList.elementAt(i);
            Command cmd = new Command("Spoj " + (i + 1) + ": " + r.title(), Command.SCREEN, 2 + i);
            rideCommands.addElement(cmd);
            addCommand(cmd);
        }
        addCommand(REFRESH_COMMAND);
        setCommandListener(this);

        build();
    }

    private synchronized void build() {
        deleteAll();
        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        RoutePart first = conn.firstRide();
        RoutePart last = conn.lastRide();
        StringItem head = new StringItem(null,
            (first != null && first.boarding() != null ? first.boarding().name : "?") + " " + Fmt.time(conn.departureIso)
            + "  ->  " + (last != null && last.alighting() != null ? last.alighting().name : "?") + " " + Fmt.time(conn.arrivalIso)
            + "\n" + Fmt.duration(conn.durationSeconds) + ", " + Fmt.transfers(conn.transferCount)
            + ", " + Fmt.day(conn.departureIso));
        head.setFont(bold);
        head.setLayout(Item.LAYOUT_NEWLINE_AFTER);
        append(head);

        int rideNo = 0;
        for (int i = 0; i < conn.parts.size(); i++) {
            RoutePart p = (RoutePart) conn.parts.elementAt(i);
            if (p.kind == RoutePart.RIDE) {
                rideNo++;
                addRide(p, rideNo, bold, small);
            }
            else if (p.kind == RoutePart.WALK) {
                int min = Fmt.minutesBetween(p.departureIso, p.arrivalIso);
                StringItem s = new StringItem("Pěšky:", p.distance + " m, " + min + " min");
                s.setLayout(Item.LAYOUT_NEWLINE_AFTER);
                append(s);
            }
            else {
                int min = Fmt.minutesBetween(p.departureIso, p.arrivalIso);
                StringItem s = new StringItem("Přestup:", "čekání " + min + " min");
                s.setLayout(Item.LAYOUT_NEWLINE_AFTER);
                append(s);
            }
        }

        for (int i = 0; i < conn.payments.size(); i++) {
            StringItem s = new StringItem(null, (String) conn.payments.elementAt(i));
            s.setFont(small);
            s.setLayout(Item.LAYOUT_NEWLINE_AFTER);
            append(s);
        }
    }

    private void addRide(RoutePart r, int no, Font bold, Font small) {
        StringBuffer title = new StringBuffer();
        title.append(no).append(". ").append(r.title());
        if (r.agency.length() > 0) title.append(" (").append(r.agency).append(")");
        String delay = Fmt.delay(r.delaySeconds());
        if (delay.length() > 0) title.append("  ").append(delay);

        StringItem t = new StringItem(null, title.toString());
        t.setFont(bold);
        t.setLayout(Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
        append(t);

        StringBuffer sb = new StringBuffer();
        for (int i = r.startIndex; i <= r.endIndex && i < r.stops.size(); i++) {
            boolean isFirst = i == r.startIndex, isLast = i == r.endIndex;
            if (!allStops && !isFirst && !isLast) continue;
            TripStop s = (TripStop) r.stops.elementAt(i);

            String time = isFirst ? Fmt.time(s.departureIso) : Fmt.time(s.arrivalIso);
            if (sb.length() > 0) sb.append('\n');
            sb.append(isFirst || isLast ? "" : "   ").append(time).append("  ").append(s.name);

            String plat = Fmt.platform(r, s, isFirst, isLast);
            if (plat != null) sb.append("  [nást. ").append(plat).append("]");
            String notes = Fmt.stopNotes(s, r);
            if (notes.length() > 0) sb.append("  (").append(notes).append(")");
        }
        StringItem stops = new StringItem(null, sb.toString());
        stops.setLayout(Item.LAYOUT_NEWLINE_AFTER);
        append(stops);

        StringBuffer extra = new StringBuffer();
        String am = Fmt.amenities(r);
        if (am.length() > 0) extra.append(am);
        Vector w = r.warnings();
        for (int i = 0; i < w.size(); i++) {
            Info x = (Info) w.elementAt(i);
            if (extra.length() > 0) extra.append('\n');
            extra.append("! ").append(x.text.trim());
        }
        if (extra.length() > 0) {
            StringItem e = new StringItem(null, extra.toString());
            e.setFont(small);
            e.setLayout(Item.LAYOUT_NEWLINE_AFTER);
            append(e);
        }
    }

    private void refreshLive() {
        final RouteScreen self = this;
        RequestCallback cb = new RequestCallback() {
            public Object request() throws Exception {
                PubtranApi.refreshLive(rideList);
                return null;
            }
            public void onSuccess(Object result) {
                build();
                App.disp.setCurrent(self);
            }
        };
        new RequestThread(cb, this).start();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == BACK_COMMAND) {
            App.disp.setCurrent(back);
        }
        else if (c == ALL_STOPS_COMMAND || c == ENDS_ONLY_COMMAND) {
            allStops = (c == ALL_STOPS_COMMAND);
            removeCommand(allStops ? ALL_STOPS_COMMAND : ENDS_ONLY_COMMAND);
            addCommand(allStops ? ENDS_ONLY_COMMAND : ALL_STOPS_COMMAND);
            build();
        }
        else if (c == REFRESH_COMMAND) {
            refreshLive();
        }
        else {
            int idx = rideCommands.indexOf(c);
            if (idx >= 0) {
                new TripScreen((RoutePart) rideList.elementAt(idx), search, this).open();
            }
        }
    }
}
