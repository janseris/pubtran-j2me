package pubtran;


import java.util.Vector;

/** One search result (a getroutesopt "route"). */
public class Route {
    public long hash;
    public String departureIso = "";
    public String arrivalIso = "";
    public int durationSeconds;
    public int transferCount;
    /** RoutePart each. */
    public Vector parts = new Vector();
    /** Human-readable ticket/price lines. */
    public Vector payments = new Vector();

    public Vector rides() {
        Vector v = new Vector();
        for (int i = 0; i < parts.size(); i++) {
            RoutePart p = (RoutePart) parts.elementAt(i);
            if (p.kind == RoutePart.RIDE) v.addElement(p);
        }
        return v;
    }

    public RoutePart firstRide() {
        Vector r = rides();
        return r.size() > 0 ? (RoutePart) r.elementAt(0) : null;
    }

    public RoutePart lastRide() {
        Vector r = rides();
        return r.size() > 0 ? (RoutePart) r.elementAt(r.size() - 1) : null;
    }

    /** Biggest current delay of any ride, or Integer.MIN_VALUE when no realtime data. */
    public int maxDelaySeconds() {
        int max = Integer.MIN_VALUE;
        Vector r = rides();
        for (int i = 0; i < r.size(); i++) {
            int d = ((RoutePart) r.elementAt(i)).delaySeconds();
            if (d > max) max = d;
        }
        return max;
    }
}
