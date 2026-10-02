package pubtran;


/**
 * A place from the suggest endpoint (city, stop, street, address, POI...). Only the
 * fields getroutesopt needs back (source + id + coordinates) plus the two display rows.
 */
public class Place {
    public String name = "";         // suggestFirstRow
    public String description = "";  // suggestSecondRow
    public String source = "";       // muni = city, pubt = stop, ... (sent back as-is)
    public Long id;                   // may be null
    public Double lat;                // may be null
    public Double lon;                // may be null

    static Place fromSuggest(FrpcStruct item) {
        FrpcStruct u = item.getStruct("userData");
        if (u == null) u = new FrpcStruct();
        Place p = new Place();
        p.name = u.getString("suggestFirstRow", "");
        p.description = u.getString("suggestSecondRow", "");
        p.source = u.getString("source", "");
        if (u.has("id")) p.id = new Long(u.getLong("id", 0));
        p.lat = u.getDouble("latitude");
        p.lon = u.getDouble("longitude");
        return p;
    }

    /** Place as getroutesopt expects it in searchOpts.start / end / mid. */
    FrpcStruct toFrpc() {
        FrpcStruct s = new FrpcStruct();
        if (lat != null && lon != null) {
            s.put("x", lon);
            s.put("y", lat);
        }
        if (source.length() > 0) s.put("source", source);
        if (id != null) s.put("id", id);
        return s;
    }

    public String kind() {
        if (source.equals("muni")) return "obec";
        if (source.equals("pubt")) return "zastávka";
        return "místo";
    }

    public boolean sameAs(Place o) {
        if (o == null) return false;
        return name.equals(o.name) && source.equals(o.source)
            && (id == null ? o.id == null : id.equals(o.id));
    }

    // --- one-line serialization for RMS (RecentPlaces) ---------------------------------

    String serialize() {
        return clean(name) + "\t" + clean(description) + "\t" + source + "\t"
            + (id == null ? "" : id.toString()) + "\t"
            + (lat == null ? "" : lat.toString()) + "\t"
            + (lon == null ? "" : lon.toString());
    }

    static Place deserialize(String line) {
        String[] f = Fmt.split(line, '\t');
        if (f.length < 6) return null;
        Place p = new Place();
        p.name = f[0];
        p.description = f[1];
        p.source = f[2];
        try {
            if (f[3].length() > 0) p.id = new Long(Long.parseLong(f[3]));
            if (f[4].length() > 0) p.lat = new Double(Double.parseDouble(f[4]));
            if (f[5].length() > 0) p.lon = new Double(Double.parseDouble(f[5]));
        }
        catch (Exception e) {
            return null;
        }
        return p;
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }
}
