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
public class TripScreen extends CardCanvas implements CommandListener {
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
        setTitle(original.title());
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
        RoutePart r = current;
        setTitle(r.title() + "  " + positionLabel());
        java.util.Vector rs = new java.util.Vector();
        rs.addElement(new HeadRow(r));
        TimelineRow t = new TimelineRow(r);
        rs.addElement(t);

        StringBuffer extra = new StringBuffer();
        String am = Fmt.amenities(r);
        if (am.length() > 0) rs.addElement(new TextRow("Vybavení: " + am, Theme.SMALL, Theme.TEXT_DIM));
        java.util.Vector w = r.warnings();
        for (int i = 0; i < w.size(); i++) {
            Info x = (Info) w.elementAt(i);
            rs.addElement(new TextRow("! " + x.text.trim() + (x.url != null ? " (" + x.url + ")" : ""), Theme.SMALL, Theme.WARNING));
        }
        rs.addElement(new TextRow("tripId: " + r.partDescription, Theme.SMALL, Theme.TEXT_FAINT));
        setRows(rs, -1);
        // start at the boarding stop
        scrollToRow(1, t.lineH() * Math.max(0, r.startIndex - 1));
    }

    /** Line badge, name, carrier, delay; boarding -> alighting with times; which run. */
    private class HeadRow extends Row {
        private final RoutePart r;

        HeadRow(RoutePart r) {
            this.r = r;
        }

        public int height(int w) {
            return 4 + Theme.badgeHeight() + 2 + Theme.SMALL.getHeight() + 4;
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            card(g, x, y, w, height(w), false);
            int lx = x + 8;
            int ly = y + 4;
            int bw = Theme.drawBadge(g, r, lx, ly);
            int delay = r.delaySeconds();
            int dw = Theme.delayWidth(delay);
            g.setFont(Theme.BOLD);
            g.setColor(Theme.TEXT);
            Theme.drawClipped(g, r.title() + (r.agency.length() > 0 ? "  (" + r.agency + ")" : ""),
                lx + bw + 8, ly + (Theme.badgeHeight() - Theme.BOLD.getHeight()) / 2, x + w - 12 - dw - (lx + bw + 8));
            if (dw > 0) Theme.drawDelay(g, delay, x + w - 6 - dw, ly + (Theme.badgeHeight() - Theme.SMALL_BOLD.getHeight() - 2) / 2);
            TripStop b = r.boarding();
            TripStop a = r.alighting();
            g.setFont(Theme.SMALL);
            g.setColor(Theme.TEXT_DIM);
            Theme.drawClipped(g, (b != null ? b.name : "?") + " " + Fmt.timeWithDay(r.departureIso, original.departureIso)
                + "  ->  " + (a != null ? a.name : "?") + " " + Fmt.timeWithDay(r.arrivalIso, original.departureIso)
                + "  (" + Fmt.minutesBetween(r.departureIso, r.arrivalIso) + " min)   " + positionLabel() + "   < >",
                lx, ly + Theme.badgeHeight() + 2, w - 16);
        }
    }

    /**
     * All stops of the trip on a vertical line: in the line colour for the ridden part,
     * grey outside it; big dots at boarding and alighting. Columns: arrival, departure,
     * stop, platform, zone, notes.
     */
    private class TimelineRow extends Row {
        private final RoutePart r;

        TimelineRow(RoutePart r) {
            this.r = r;
        }

        int lineH() {
            return Theme.PLAIN.getHeight() + 1;
        }

        public int height(int w) {
            return r.stops.size() * lineH();
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            int n = r.stops.size();
            int lh = lineH();
            int cx = x + 10;
            int timeW = Theme.PLAIN.stringWidth("00:00") + 6;
            int clipTop = g.getClipY(), clipBottom = clipTop + g.getClipHeight();
            int color = Theme.color(r);
            for (int i = 0; i < n; i++) {
                int ly = y + i * lh;
                if (ly + lh < clipTop || ly > clipBottom) continue;
                TripStop s = (TripStop) r.stops.elementAt(i);
                boolean ridden = i >= r.startIndex && i <= r.endIndex;
                boolean endpoint = i == r.startIndex || i == r.endIndex;
                int mid = ly + lh / 2;

                // line segments above and below the dot
                if (i > 0) {
                    g.setColor(i > r.startIndex && i <= r.endIndex ? color : Theme.SEPARATOR);
                    g.fillRect(cx - 1, ly, 3, lh / 2);
                }
                if (i < n - 1) {
                    g.setColor(i >= r.startIndex && i < r.endIndex ? color : Theme.SEPARATOR);
                    g.fillRect(cx - 1, mid, 3, lh - lh / 2);
                }
                int d = endpoint ? 9 : 5;
                g.setColor(ridden ? color : Theme.SEPARATOR);
                g.fillArc(cx - d / 2, mid - d / 2, d, d, 0, 360);
                if (endpoint) {
                    g.setColor(Theme.BG);
                    g.fillArc(cx - 2, mid - 2, 4, 4, 0, 360);
                }

                Font f = endpoint ? Theme.BOLD : Theme.PLAIN;
                int tc = endpoint ? Theme.TEXT : (ridden ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
                g.setFont(f);
                g.setColor(tc);
                int tx = cx + 12;
                if (i > 0) g.drawString(Fmt.time(s.arrivalIso), tx, ly, Graphics.TOP | Graphics.LEFT);
                if (i < n - 1) g.drawString(Fmt.time(s.departureIso), tx + timeW, ly, Graphics.TOP | Graphics.LEFT);
                int nx = tx + 2 * timeW + 4;
                g.drawString(s.name, nx, ly, Graphics.TOP | Graphics.LEFT);
                nx += f.stringWidth(s.name) + 8;

                StringBuffer more = new StringBuffer();
                String plat = Fmt.platform(r, s, i == r.startIndex, i == r.endIndex);
                if (plat != null) more.append("nást. ").append(plat);
                String zone = s.code(Info.STOP_ZONE);
                if (zone != null && zone.length() > 0) more.append(more.length() > 0 ? ", " : "").append("pásmo ").append(zone);
                String notes = Fmt.stopNotes(s, r);
                if (notes.length() > 0) more.append(more.length() > 0 ? ", " : "").append(notes);
                if (more.length() > 0) {
                    g.setFont(Theme.SMALL);
                    g.setColor(Theme.TEXT_FAINT);
                    Theme.drawClipped(g, more.toString(), nx, ly + 1, x + w - 6 - nx);
                }
            }
        }
    }

    protected void onLeft() {
        load(offset - 1, false);
    }

    protected void onRight() {
        load(offset + 1, false);
    }

    private String positionLabel() {
        if (offset == 0) return "(zvolený)";
        if (offset > 0) return "(" + offset + ". následující)";
        return "(" + (-offset) + ". předchozí)";
    }

    public void commandAction(Command c, Displayable d) {
        if (handleCommand(c)) return;
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
