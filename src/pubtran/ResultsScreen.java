package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Search results. The first and last rows replace the app's scrolling up/down:
 * "<< Dřívější spoje" loads the previous page (index -5 relative to the first one)
 * and "Další spoje >>" the next (+5), always with the same "when" and the hashes of
 * everything already shown, exactly like the app does.
 *
 * Delays come with each page (the search response carries realtime info). Fresh
 * ones (gettripinfos) only on "Obnovit zpoždění" - on the 9300 every request is a
 * full TLS handshake, so nothing is fetched automatically in the background.
 */
public class ResultsScreen extends List implements CommandListener {
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 0);
    private static final Command EARLIER_COMMAND = new Command("Dřívější spoje", Command.SCREEN, 1);
    private static final Command LATER_COMMAND = new Command("Další spoje", Command.SCREEN, 2);
    private static final Command REFRESH_COMMAND = new Command("Obnovit zpoždění", Command.SCREEN, 3);
    private static final Command LOG_COMMAND = new Command("Log požadavků", Command.SCREEN, 4);

    private static final String[] WEEKDAYS = {"ne", "po", "út", "st", "čt", "pá", "so"};

    private final SearchState search;
    private final String when;
    private final Displayable back;
    /** Route, sorted by departure. */
    private final Vector conns;
    private int minIndex = 0;
    private int maxIndex = SearchState.COUNT;

    public ResultsScreen(SearchState search, String when, Vector conns, Displayable back) {
        super(search.from.name + " - " + search.to.name, List.IMPLICIT);
        this.search = search;
        this.when = when;
        this.back = back;
        this.conns = new Vector();
        addAll(conns);

        addCommand(BACK_COMMAND);
        addCommand(EARLIER_COMMAND);
        addCommand(LATER_COMMAND);
        addCommand(REFRESH_COMMAND);
        addCommand(LOG_COMMAND);
        setCommandListener(this);

        rebuild(1);
    }

    /** Adds connections not shown yet, keeping the list sorted by departure. Returns how many were new. */
    private int addAll(Vector more) {
        int added = 0;
        for (int i = 0; i < more.size(); i++) {
            Route c = (Route) more.elementAt(i);
            boolean dup = false;
            for (int j = 0; j < conns.size(); j++) {
                if (((Route) conns.elementAt(j)).hash == c.hash) { dup = true; break; }
            }
            if (dup) continue;
            int pos = conns.size();
            while (pos > 0 && compare((Route) conns.elementAt(pos - 1), c) > 0) pos--;
            conns.insertElementAt(c, pos);
            added++;
        }
        return added;
    }

    private static int compare(Route a, Route b) {
        long da = unix(a.departureIso), db = unix(b.departureIso);
        if (da != db) return da < db ? -1 : 1;
        long aa = unix(a.arrivalIso), ab = unix(b.arrivalIso);
        return aa == ab ? 0 : (aa < ab ? -1 : 1);
    }

    private static long unix(String iso) {
        try {
            return FrpcDate.fromIso(iso).unixSeconds;
        }
        catch (Exception e) {
            return 0;
        }
    }

    private Vector hashes() {
        Vector v = new Vector();
        for (int i = 0; i < conns.size(); i++) v.addElement(new Long(((Route) conns.elementAt(i)).hash));
        return v;
    }

    /** Rebuilds the rows; select = list index to select afterwards. */
    private synchronized void rebuild(int select) {
        deleteAll();
        append("<< Dřívější spoje", null);
        String prevDay = datePart(when);
        for (int i = 0; i < conns.size(); i++) {
            Route c = (Route) conns.elementAt(i);
            String day = Fmt.datePart(c.departureIso);
            String prefix = "";
            if (!day.equals(prevDay)) {
                prefix = "[" + weekday(c.departureIso) + " " + Fmt.day(c.departureIso) + "] ";
                prevDay = day;
            }
            append(prefix + Fmt.connectionLabel(c), null);
        }
        append("Další spoje >>", null);
        if (select >= 0 && select < size()) setSelectedIndex(select, true);
    }

    private static String datePart(String when) {
        return when.length() >= 10 ? when.substring(0, 10) : when;
    }

    private static String weekday(String iso) {
        try {
            return WEEKDAYS[FrpcDate.fromIso(iso).weekday];
        }
        catch (Exception e) {
            return "";
        }
    }

    // --- paging ---------------------------------------------------------------------

    private void loadPage(final boolean later) {
        final int index = later ? maxIndex : minIndex - SearchState.COUNT;
        final Vector used = hashes();
        final ResultsScreen self = this;

        RequestCallback cb = new RequestCallback() {
            public Object request() throws Exception {
                return PubtranApi.search(search, when, index, used);
            }
            public void onSuccess(Object result) {
                Vector page = (Vector) result;
                int firstNew = findFirstNew(page);
                int added = addAll(page);
                if (later) maxIndex = index + SearchState.COUNT;
                else minIndex = index;

                int select;
                if (added == 0) select = later ? size() - 1 : 0;
                else if (later) select = firstNew >= 0 ? indexOf(firstNew, page) + 1 : 1;
                else select = 1;
                rebuild(select);
                App.disp.setCurrent(self);

                if (added == 0) {
                    Alert a = new Alert("Spojení", later ? "Žádné další spoje." : "Žádné dřívější spoje.", null, AlertType.INFO);
                    a.setTimeout(2000);
                    App.disp.disp.setCurrent(a, self);
                }
                // delays come with the page itself; fresh ones only on "Obnovit zpoždění"
            }
        };
        new RequestThread(cb, this).start();
    }

    /** Index in page of the earliest new connection, or -1. */
    private int findFirstNew(Vector page) {
        Vector used = hashes();
        for (int i = 0; i < page.size(); i++) {
            if (!used.contains(new Long(((Route) page.elementAt(i)).hash))) return i;
        }
        return -1;
    }

    /** Position of page[i] in conns (after it was added). */
    private int indexOf(int i, Vector page) {
        long h = ((Route) page.elementAt(i)).hash;
        for (int j = 0; j < conns.size(); j++) {
            if (((Route) conns.elementAt(j)).hash == h) return j;
        }
        return 0;
    }

    // --- realtime -------------------------------------------------------------------

    /** gettripinfos for all rides of the given connections, then relabel. Silent on errors. */
    public void refreshLiveInBackground(final Vector which) {
        new Thread() {
            public void run() {
                Vector rides = new Vector();
                for (int i = 0; i < which.size(); i++) {
                    Vector r = ((Route) which.elementAt(i)).rides();
                    for (int j = 0; j < r.size(); j++) rides.addElement(r.elementAt(j));
                }
                try {
                    PubtranApi.refreshLive(rides);
                }
                catch (Exception e) {
                    return;
                }
                int sel = getSelectedIndex();
                rebuild(sel);
            }
        }.start();
    }

    // --- commands -------------------------------------------------------------------

    public void commandAction(Command c, Displayable d) {
        if (c == BACK_COMMAND) {
            App.disp.setCurrent(back);
        }
        else if (c == EARLIER_COMMAND) {
            loadPage(false);
        }
        else if (c == LATER_COMMAND) {
            loadPage(true);
        }
        else if (c == REFRESH_COMMAND) {
            refreshLiveInBackground(conns);
        }
        else if (c == LOG_COMMAND) {
            App.disp.setCurrent(new LogScreen(this));
        }
        else if (c == List.SELECT_COMMAND) {
            int idx = getSelectedIndex();
            if (idx == 0) loadPage(false);
            else if (idx == size() - 1) loadPage(true);
            else if (idx > 0 && idx - 1 < conns.size()) {
                App.disp.setCurrent(new RouteScreen((Route) conns.elementAt(idx - 1), search, this));
            }
        }
    }
}
