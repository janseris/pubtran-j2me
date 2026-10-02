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
public class RouteScreen extends CardCanvas implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);
    private static final Command ALL_STOPS_COMMAND = new Command("Všechny zastávky", Command.SCREEN, 1);
    private static final Command ENDS_ONLY_COMMAND = new Command("Jen nástup a výstup", Command.SCREEN, 1);
    private static final Command REFRESH_COMMAND = new Command("Obnovit zpoždění", Command.SCREEN, 9);
    private static final Command OPEN_COMMAND = new Command("Detail spoje", Command.OK, 1);

    private final Route conn;
    private final SearchState search;
    private final Displayable back;
    private boolean allStops = false;

    /** One Command per ride, same order as rideList. */
    private final Vector rideCommands = new Vector();
    private final Vector rideList;

    public RouteScreen(Route conn, SearchState search, Displayable back) {
        setTitle(Fmt.time(conn.departureIso) + " - " + Fmt.time(conn.arrivalIso));
        this.conn = conn;
        this.search = search;
        this.back = back;
        this.rideList = conn.rides();

        addCommand(OPEN_COMMAND);
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
        int keep = focus;
        Vector r = new Vector();
        r.addElement(new HeaderRow());
        int rideNo = 0;
        for (int i = 0; i < conn.parts.size(); i++) {
            RoutePart p = (RoutePart) conn.parts.elementAt(i);
            if (p.kind == RoutePart.RIDE) {
                rideNo++;
                r.addElement(new RideRow(p, rideNo));
            }
            else {
                r.addElement(new GapRow(p));
            }
        }
        for (int i = 0; i < conn.payments.size(); i++) {
            r.addElement(new TextRow((String) conn.payments.elementAt(i), Theme.SMALL, Theme.TEXT_DIM));
        }
        setRows(r, keep);
    }

    /** Whole connection: from -> to with times, duration, transfers, date. */
    private class HeaderRow extends Row {
        public int height(int w) {
            return Theme.BOLD.getHeight() + Theme.SMALL.getHeight() + 2;
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            RoutePart first = conn.firstRide();
            RoutePart last = conn.lastRide();
            String from = first != null && first.boarding() != null ? first.boarding().name : "?";
            String to = last != null && last.alighting() != null ? last.alighting().name : "?";
            g.setFont(Theme.BOLD);
            g.setColor(Theme.TEXT);
            Theme.drawClipped(g, Fmt.time(conn.departureIso) + " " + from + "  ->  " + Fmt.time(conn.arrivalIso) + " " + to,
                x + 4, y, w - 8);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.TEXT_DIM);
            g.drawString(Fmt.duration(conn.durationSeconds) + ", " + Fmt.transfers(conn.transferCount) + ", " + Fmt.day(conn.departureIso),
                x + 4, y + Theme.BOLD.getHeight() + 2, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Walking or waiting between rides: one small line with the app's icon. */
    private static class GapRow extends Row {
        private final RoutePart p;

        GapRow(RoutePart p) {
            this.p = p;
        }

        public int height(int w) {
            return Math.max(Theme.iconSize(), Theme.SMALL.getHeight());
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            int min = Fmt.minutesBetween(p.departureIso, p.arrivalIso);
            Theme.drawIcon(g, p.kind == RoutePart.WALK ? Theme.I_WALK : Theme.I_WAIT, x + 10, y);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.TEXT_DIM);
            String t = p.kind == RoutePart.WALK ? "Pěšky " + p.distance + " m, " + min + " min" : "Přestup, čekání " + min + " min";
            g.drawString(t, x + 10 + Theme.iconSize() + 6, y + (height(w) - Theme.SMALL.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        }
    }

    /**
     * One ride: line badge, name, carrier and delay, then the boarding / alighting stop
     * (and the stops in between with "Všechny zastávky"), amenities and warnings. A stripe
     * in the line colour runs down the left side. Enter opens the whole trip.
     */
    private class RideRow extends Row {
        private final RoutePart r;
        private final int no;
        private Vector stopIdx;
        private Vector extraLines;
        private int lw = -1;

        RideRow(RoutePart r, int no) {
            this.r = r;
            this.no = no;
            focusable = true;
        }

        private void prepare(int w) {
            if (lw == w) return;
            lw = w;
            stopIdx = new Vector();
            for (int i = r.startIndex; i <= r.endIndex && i < r.stops.size(); i++) {
                if (allStops || i == r.startIndex || i == r.endIndex) stopIdx.addElement(new Integer(i));
            }
            extraLines = new Vector();
            String am = Fmt.amenities(r);
            if (am.length() > 0) addWrapped(am, w, Theme.TEXT_DIM);
            Vector warn = r.warnings();
            for (int i = 0; i < warn.size(); i++) addWrapped("! " + ((Info) warn.elementAt(i)).text.trim(), w, Theme.WARNING);
        }

        private void addWrapped(String text, int w, int color) {
            Vector l = Theme.wrap(text, Theme.SMALL, w - 20);
            for (int i = 0; i < l.size(); i++) extraLines.addElement(new Object[] {l.elementAt(i), new Integer(color)});
        }

        private int stopLineH() {
            return Theme.PLAIN.getHeight() + 1;
        }

        public int height(int w) {
            prepare(w);
            return 4 + Theme.badgeHeight() + 3 + stopIdx.size() * stopLineH()
                + extraLines.size() * Theme.SMALL.getHeight() + 4;
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            prepare(w);
            int h = height(w);
            card(g, x, y, w, h, focus);
            g.setColor(Theme.color(r));
            g.fillRect(x + 3, y + 4, 4, h - 8);

            int lx = x + 12;
            int ly = y + 4;
            int bw = Theme.drawBadge(g, r, lx, ly);
            String title = r.title() + (r.agency.length() > 0 ? "  (" + r.agency + ")" : "");
            int delay = r.delaySeconds();
            int dw = Theme.delayWidth(delay);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.TEXT_DIM);
            Theme.drawClipped(g, no + ". " + title, lx + bw + 8, ly + (Theme.badgeHeight() - Theme.SMALL.getHeight()) / 2,
                x + w - 12 - dw - (lx + bw + 8));
            if (dw > 0) Theme.drawDelay(g, delay, x + w - 6 - dw, ly + (Theme.badgeHeight() - Theme.SMALL_BOLD.getHeight() - 2) / 2);

            int sy = ly + Theme.badgeHeight() + 3;
            int timeW = Theme.BOLD.stringWidth("00:00") + 8;
            for (int k = 0; k < stopIdx.size(); k++) {
                int i = ((Integer) stopIdx.elementAt(k)).intValue();
                TripStop s = (TripStop) r.stops.elementAt(i);
                boolean isFirst = i == r.startIndex, isLast = i == r.endIndex;
                boolean end = isFirst || isLast;
                String time = isFirst ? Fmt.time(s.departureIso) : Fmt.time(s.arrivalIso);
                g.setFont(end ? Theme.BOLD : Theme.PLAIN);
                g.setColor(end ? Theme.TEXT : Theme.TEXT_DIM);
                g.drawString(time, lx, sy, Graphics.TOP | Graphics.LEFT);
                int nx = lx + timeW;
                String plat = Fmt.platform(r, s, isFirst, isLast);
                String notes = Fmt.stopNotes(s, r);
                int nw = g.getFont().stringWidth(s.name);
                g.drawString(s.name, nx, sy, Graphics.TOP | Graphics.LEFT);
                nx += nw + 8;
                if (plat != null) {
                    String pt = "nást. " + plat;
                    int pw = Theme.SMALL_BOLD.stringWidth(pt) + 8;
                    g.setColor(Theme.HEADER);
                    g.fillRoundRect(nx, sy + 1, pw, Theme.SMALL_BOLD.getHeight(), 6, 6);
                    g.setColor(Theme.TEXT);
                    g.setFont(Theme.SMALL_BOLD);
                    g.drawString(pt, nx + 4, sy + 1, Graphics.TOP | Graphics.LEFT);
                    nx += pw + 6;
                }
                if (notes.length() > 0) {
                    g.setFont(Theme.SMALL);
                    g.setColor(Theme.TEXT_FAINT);
                    Theme.drawClipped(g, notes, nx, sy + 1, x + w - 6 - nx);
                }
                sy += stopLineH();
            }
            g.setFont(Theme.SMALL);
            for (int k = 0; k < extraLines.size(); k++) {
                Object[] e = (Object[]) extraLines.elementAt(k);
                g.setColor(((Integer) e[1]).intValue());
                g.drawString((String) e[0], lx, sy, Graphics.TOP | Graphics.LEFT);
                sy += Theme.SMALL.getHeight();
            }
        }

        public void select() {
            new TripScreen(r, search, RouteScreen.this).open();
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
        if (handleCommand(c)) return;
        if (c == OPEN_COMMAND) {
            if (focus >= 0) row(focus).select();
        }
        else if (c == BACK_COMMAND) {
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
