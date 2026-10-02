package pubtran;

import javax.microedition.lcdui.*;

/**
 * Colours and small drawing helpers shared by the hand-drawn screens.
 *
 * Backgrounds and the focus colour are the start screen's (TileScreen). Transport
 * and delay colours are the Android app's dark-mode colours (res/values-night:
 * transport_type_*, delay_background_*), and the vehicle icons in
 * /transport16.png are the app's night-mode drawables scaled to 16 px.
 */
public class Theme {
    // --- start screen palette ---
    public static final int BG = 0x2B2D31;
    public static final int CARD = 0x383A40;
    public static final int CARD_FOCUS_BORDER = 0x5865F2;
    public static final int HEADER = 0x1E1F22;
    public static final int SEPARATOR = 0x4E5058;
    public static final int TEXT = 0xFFFFFF;
    public static final int TEXT_DIM = 0xB5BAC1;
    public static final int TEXT_FAINT = 0x80848E;
    public static final int BUTTON = 0x4E5058;
    public static final int GREEN = 0x248046;

    // --- Android app, dark mode ---
    public static final int ON_TIME = 0x81C784;   // delay_background_green
    public static final int LATE = 0xEE6C6C;      // delay_background_red
    public static final int NO_DATA = 0x999999;   // delay_background_gray
    public static final int WARNING = 0xFCEE74;   // transport_type_metro_b (yellow)

    public static final int BUS = 0xB88861;
    public static final int TRAM = 0x98C7F1;
    public static final int TRAIN = 0x67B8F2;
    public static final int TROLLEY = 0x5FBAB0;
    public static final int METRO = 0x666666;
    public static final int METRO_A = 0x81C784;
    public static final int METRO_B = 0xFCEE74;
    public static final int METRO_C = 0xEE6C6C;
    public static final int BOAT = 0x7D89C3;
    public static final int ROPEWAY = 0x7DC39E;
    public static final int WALK = 0x9B9B9B;

    public static final Font BOLD = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    public static final Font PLAIN = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    public static final Font SMALL = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    public static final Font SMALL_BOLD = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    public static final Font LARGE_BOLD = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);

    // icon order in /transport16.png
    private static final int ICON_SIZE = 16;
    public static final int I_BUS = 0, I_TRAM = 1, I_TRAIN = 2, I_METRO = 3, I_TROLLEY = 4, I_BOAT = 5,
        I_ROPEWAY = 6, I_METRO_A = 7, I_METRO_B = 8, I_METRO_C = 9, I_WALK = 10, I_WAIT = 11;

    private static Image icons;
    private static boolean iconsTried;

    private static Image icons() {
        if (!iconsTried) {
            iconsTried = true;
            try { icons = Image.createImage("/transport16.png"); } catch (Throwable e) { icons = null; }
        }
        return icons;
    }

    /** Line colour for a ride (vehicleType from the app's TransportType; metro by line letter). */
    public static int color(RoutePart r) {
        switch (r.vehicleType) {
            case 1: return BUS;
            case 2: return TRAM;
            case 3: return ROPEWAY;
            case 4:
                if ("A".equals(r.routeName)) return METRO_A;
                if ("B".equals(r.routeName)) return METRO_B;
                if ("C".equals(r.routeName)) return METRO_C;
                return METRO;
            case 5: return BOAT;
            case 6: return TROLLEY;
            case 7: return TRAIN;
            default: return WALK;
        }
    }

    public static int icon(RoutePart r) {
        switch (r.vehicleType) {
            case 1: return I_BUS;
            case 2: return I_TRAM;
            case 3: return I_ROPEWAY;
            case 4:
                if ("A".equals(r.routeName)) return I_METRO_A;
                if ("B".equals(r.routeName)) return I_METRO_B;
                if ("C".equals(r.routeName)) return I_METRO_C;
                return I_METRO;
            case 5: return I_BOAT;
            case 6: return I_TROLLEY;
            case 7: return I_TRAIN;
            default: return I_BUS;
        }
    }

    /** Draws icon #index with its top-left at x, y (nothing if the sprite couldn't load). */
    public static void drawIcon(Graphics g, int index, int x, int y) {
        Image im = icons();
        if (im == null) return;
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        g.clipRect(x, y, ICON_SIZE, ICON_SIZE);
        g.drawImage(im, x - index * ICON_SIZE, y, Graphics.TOP | Graphics.LEFT);
        g.setClip(cx, cy, cw, ch);
    }

    public static int iconSize() {
        return ICON_SIZE;
    }

    /** Short line name for a badge: routeName, or the long one for trains ("R12"). */
    public static String lineName(RoutePart r) {
        String n = r.routeName;
        if (r.vehicleType == 7 && r.routeLongName.length() > 0) {
            // trains: "R12 / R 914" -> "R12"; keep it short in a badge
            n = r.routeLongName;
            int slash = n.indexOf(" / ");
            if (slash > 0) n = n.substring(0, slash);
        }
        return n;
    }

    /**
     * Line badge: the vehicle icon, then the line name on a rounded box in the line's
     * colour (dark text, as in the app). Returns its width. Height = badgeHeight().
     */
    public static int drawBadge(Graphics g, RoutePart r, int x, int y) {
        int h = badgeHeight();
        int iy = y + (h - ICON_SIZE) / 2;
        drawIcon(g, icon(r), x, iy);
        String name = lineName(r);
        Font f = SMALL_BOLD;
        int tw = f.stringWidth(name);
        int bx = x + ICON_SIZE + 2;
        int bw = tw + 8;
        g.setColor(color(r));
        g.fillRoundRect(bx, y, bw, h, 6, 6);
        g.setColor(0x1A1A1A);
        g.setFont(f);
        g.drawString(name, bx + 4, y + (h - f.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        return ICON_SIZE + 2 + bw;
    }

    public static int badgeWidth(RoutePart r) {
        return ICON_SIZE + 2 + SMALL_BOLD.stringWidth(lineName(r)) + 8;
    }

    public static int badgeHeight() {
        return Math.max(ICON_SIZE, SMALL_BOLD.getHeight() + 2);
    }

    /** Delay pill: green "včas", red "+5 min"; nothing (width 0) without realtime data. */
    public static int drawDelay(Graphics g, int seconds, int x, int y) {
        String t = Fmt.delay(seconds);
        if (t.length() == 0) return 0;
        Font f = SMALL_BOLD;
        int w = f.stringWidth(t) + 8;
        int h = f.getHeight() + 2;
        g.setColor(seconds <= 59 ? ON_TIME : LATE);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setColor(0x1A1A1A);
        g.setFont(f);
        g.drawString(t, x + 4, y + 1, Graphics.TOP | Graphics.LEFT);
        return w;
    }

    public static int delayWidth(int seconds) {
        String t = Fmt.delay(seconds);
        return t.length() == 0 ? 0 : SMALL_BOLD.stringWidth(t) + 8;
    }

    /** Draws s cut to maxWidth with "…" if needed; returns the drawn width. */
    public static int drawClipped(Graphics g, String s, int x, int y, int maxWidth) {
        Font f = g.getFont();
        if (maxWidth <= 0) return 0;
        if (f.stringWidth(s) <= maxWidth) {
            g.drawString(s, x, y, Graphics.TOP | Graphics.LEFT);
            return f.stringWidth(s);
        }
        String dots = "...";
        int dw = f.stringWidth(dots);
        int n = s.length();
        while (n > 0 && f.substringWidth(s, 0, n) + dw > maxWidth) n--;
        String out = s.substring(0, n) + dots;
        g.drawString(out, x, y, Graphics.TOP | Graphics.LEFT);
        return f.stringWidth(out);
    }

    /** Splits text into lines that fit width (by words; long words are cut). */
    public static java.util.Vector wrap(String text, Font f, int width) {
        java.util.Vector lines = new java.util.Vector();
        String[] paras = Fmt.split(text, '\n');
        for (int p = 0; p < paras.length; p++) {
            String[] words = Fmt.split(paras[p], ' ');
            StringBuffer line = new StringBuffer();
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                String cand = line.length() == 0 ? w : line + " " + w;
                if (f.stringWidth(cand) <= width || line.length() == 0) {
                    line.setLength(0);
                    line.append(cand);
                }
                else {
                    lines.addElement(line.toString());
                    line.setLength(0);
                    line.append(w);
                }
            }
            lines.addElement(line.toString());
        }
        return lines;
    }
}
