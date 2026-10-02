package pubtran;


import java.util.Vector;

/** Czech formatting helpers for the pubtran screens. Times are taken straight from the
 * backend's ISO strings ("2026-09-30T20:45:00+02:00"), which are already local time. */
public class Fmt {
    public static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** "20:45" from an ISO timestamp. */
    public static String time(String iso) {
        if (iso == null || iso.length() < 16) return "";
        return iso.substring(11, 16);
    }

    /** "YYYY-MM-DD" part of an ISO timestamp. */
    public static String datePart(String iso) {
        return (iso == null || iso.length() < 10) ? "" : iso.substring(0, 10);
    }

    /** "30.9." from an ISO timestamp. */
    public static String day(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            return Integer.parseInt(iso.substring(8, 10)) + "." + Integer.parseInt(iso.substring(5, 7)) + ".";
        }
        catch (Exception e) {
            return "";
        }
    }

    /** Time, prefixed with the date when it's another day than refIso. */
    public static String timeWithDay(String iso, String refIso) {
        if (datePart(iso).equals(datePart(refIso))) return time(iso);
        return day(iso) + " " + time(iso);
    }

    /** Minutes between two ISO timestamps (via FrpcDate, so it works across midnight). */
    public static int minutesBetween(String fromIso, String toIso) {
        try {
            return (int) ((FrpcDate.fromIso(toIso).unixSeconds - FrpcDate.fromIso(fromIso).unixSeconds) / 60);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static String duration(int seconds) {
        int min = seconds / 60;
        if (min >= 60) return (min / 60) + " h " + (min % 60) + " min";
        return min + " min";
    }

    public static String transfers(int n) {
        if (n == 0) return "bez přestupu";
        if (n == 1) return "1 přestup";
        if (n >= 2 && n <= 4) return n + " přestupy";
        return n + " přestupů";
    }

    /** "" when there's no realtime info, else "včas" / "+5 min". */
    public static String delay(int seconds) {
        if (seconds == Integer.MIN_VALUE) return "";
        int min = (seconds + 30) / 60;
        if (seconds < 0) min = 0;
        return min <= 0 ? "včas" : "+" + min + " min";
    }

    /** vehicleType values from the app's TransportType.find(). */
    public static String vehicleName(int type) {
        switch (type) {
            case 1: return "Bus";
            case 2: return "Tram";
            case 3: return "Lanovka";
            case 4: return "Metro";
            case 5: return "Loď";
            case 6: return "Trolejbus";
            case 7: return "Vlak";
            default: return "Spoj";
        }
    }

    public static String amenities(RoutePart r) {
        StringBuffer sb = new StringBuffer();
        if (r.hasInfo(Info.WIFI)) append(sb, "Wi-Fi");
        if (r.hasInfo(Info.AIR_CONDITIONING)) append(sb, "klimatizace");
        if (r.hasInfo(Info.ACCESSIBLE)) append(sb, "bezbariérový");
        if (r.hasInfo(Info.BICYCLE)) append(sb, "kola");
        if (r.hasInfo(Info.NEEDS_BOOKING)) append(sb, "nutná rezervace");
        return sb.toString();
    }

    public static String stopNotes(TripStop s, RoutePart ride) {
        StringBuffer sb = new StringBuffer();
        if (s.has(Info.STOP_REQUEST)) append(sb, "na znamení");
        if (s.has(Info.STOP_ONLY_GET_OFF)) append(sb, "jen výstup");
        if (s.has(Info.STOP_ONLY_GET_ON)) append(sb, "jen nástup");
        int ud = ride.usualDelayAt(s.stopId);
        if (ud != Integer.MIN_VALUE && ud >= 60) append(sb, "obvykle +" + ((ud + 30) / 60) + " min");
        return sb.toString();
    }

    /** Platform at a stop of a ride: realtime (info 400) > stop code > ride's start/end platform. */
    public static String platform(RoutePart ride, TripStop s, boolean isBoarding, boolean isAlighting) {
        String p = ride.platformAt(s.stopId);
        if (p == null || p.length() == 0) p = s.platform();
        if ((p == null || p.length() == 0) && isBoarding) p = ride.startPlatform;
        if ((p == null || p.length() == 0) && isAlighting) p = ride.endPlatform;
        return (p == null || p.length() == 0) ? null : p;
    }

    /** One line for a getroutesopt "payment" struct. */
    static String payment(FrpcStruct p) {
        StringBuffer sb = new StringBuffer("Jízdenka");
        if ("partialprice".equals(p.getString("type", ""))) sb.append(" (část trasy)");
        sb.append(": ").append(p.getString("stopFrom", "")).append(" - ").append(p.getString("stopTo", ""));
        Double price = p.getDouble("price");
        if (price != null) {
            long whole = (long) price.doubleValue();
            String cur = p.getString("currency_type", "");
            sb.append(", ").append(whole).append(" ").append("CZK".equals(cur) ? "Kč" : cur);
        }
        String shop = p.getString("eshopName", "");
        if (shop.length() == 0) {
            FrpcStruct src = p.getStruct("source");
            if (src != null) shop = src.getString("name", "");
        }
        if (shop.length() > 0) sb.append(" (").append(shop).append(")");
        return sb.toString();
    }

    private static void append(StringBuffer sb, String s) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    /** e.g. "05:15-06:51  1 h 36 min, 1 přestup | Bus 780400 > Bus 332001 | z Olomouc, aut.nádr. (10)  +3 min" */
    public static String connectionLabel(Route c) {
        StringBuffer sb = new StringBuffer();
        sb.append(Fmt.time(c.departureIso)).append("-").append(Fmt.time(c.arrivalIso));
        sb.append("  ").append(Fmt.duration(c.durationSeconds)).append(", ").append(Fmt.transfers(c.transferCount));

        sb.append(" | ");
        Vector rides = c.rides();
        for (int i = 0; i < rides.size(); i++) {
            RoutePart r = (RoutePart) rides.elementAt(i);
            if (i > 0) sb.append(" > ");
            sb.append(Fmt.vehicleName(r.vehicleType)).append(" ").append(r.routeName);
        }

        RoutePart first = c.firstRide();
        if (first != null && first.boarding() != null) {
            sb.append(" | z ").append(first.boarding().name);
            if (first.startPlatform != null && first.startPlatform.length() > 0) {
                sb.append(" (").append(first.startPlatform).append(")");
            }
        }

        String d = Fmt.delay(c.maxDelaySeconds());
        if (d.length() > 0) sb.append("  ").append(d);
        return sb.toString();
    }

    /** CLDC's String has no split(). */
    public static String[] split(String s, char sep) {
        Vector parts = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                parts.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        String[] out = new String[parts.size()];
        parts.copyInto(out);
        return out;
    }
}
