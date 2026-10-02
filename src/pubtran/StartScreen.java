package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Start screen - the search form of the "Jízdní řády" app as a tile page (TileScreen,
 * the same hand-drawn 3D tiles as the old JSONPlaceholder test screen):
 *
 *   Odkud | Kam | Přes | Kdy | Hledat | Log
 *
 * Odkud/Kam/Přes open PlaceScreen (live suggestions + recent places), Kdy opens
 * WhenScreen (date/time, departure/arrival, filters), Hledat runs getroutesopt and
 * opens ResultsScreen, Log opens the request log (timing + native TLS details).
 *
 * While the search request is in flight this screen stays put and draws its own
 * loading overlay (dimmed tiles + spinner + live transfer progress) - LoadingHost.
 */
public class StartScreen extends TileScreen implements CommandListener, LoadingHost {
    private static final Command OPEN_COMMAND = new Command("Otevřít", Command.OK, 1);
    private static final Command SEARCH_COMMAND = new Command("Hledat", Command.SCREEN, 2);
    private static final Command SWAP_COMMAND = new Command("Prohodit Odkud/Kam", Command.SCREEN, 3);
    private static final Command NOW_COMMAND = new Command("Čas: teď", Command.SCREEN, 4);
    private static final Command INFO_COMMAND = new Command("Info", Command.HELP, 5);
    private static final Command EXIT_COMMAND = new Command("Konec", Command.EXIT, 6);
    /** Shown only while a request runs (see setLoading). */
    private static final Command CANCEL_COMMAND = new Command("Zrušit", Command.STOP, 0);

    static final int FROM = 0, TO = 1, VIA = 2, WHEN = 3, SEARCH = 4, LOG = 5, TLS_TEST = 6;

    private boolean loading = false;
    private int spinnerFrame = 0;

    public StartScreen() {
        setTitle("Jízdní řády");
        addCommand(OPEN_COMMAND);
        addCommand(SEARCH_COMMAND);
        addCommand(SWAP_COMMAND);
        addCommand(NOW_COMMAND);
        addCommand(INFO_COMMAND);
        addCommand(EXIT_COMMAND);
        setCommandListener(this);
    }

    // --- Tiles -------------------------------------------------------------------

    protected int getTileCount() {
        return 7;
    }

    protected String getTileLabel(int index) {
        SearchState s = SearchState.current;
        switch (index) {
            case FROM: return "Odkud\n" + placeLabel(s.from, "(vyberte)");
            case TO: return "Kam\n" + placeLabel(s.to, "(vyberte)");
            case VIA: return "Přes\n" + placeLabel(s.via, "(nepovinné)");
            case WHEN: return "Kdy\n" + s.whenLabel();
            case SEARCH: return "Hledat";
            case LOG: return "Log\n" + RequestLog.getEntries().size() + " požadavků";
            default: return "HTTPS test\nstránky";
        }
    }

    private static String placeLabel(Place p, String empty) {
        if (p == null) return empty;
        String n = p.name;
        return n.length() > 24 ? n.substring(0, 22) + ".." : n;
    }

    protected int getPreferredColumns() {
        return 4; // 4 x 2 on the 9300's 640x200 screen
    }

    protected int getTileColor(int index) {
        if (index == SEARCH) return 0x248046; // green, like the app's search button
        return BUTTON_COLOR;
    }

    protected boolean isInputBlocked() {
        return loading;
    }

    protected void onTileSelected(int index) {
        switch (index) {
            case FROM:
                App.disp.setCurrent(new PlaceScreen(FROM, this));
                break;
            case TO:
                App.disp.setCurrent(new PlaceScreen(TO, this));
                break;
            case VIA:
                App.disp.setCurrent(new PlaceScreen(VIA, this));
                break;
            case WHEN:
                App.disp.setCurrent(new WhenScreen(this));
                break;
            case SEARCH:
                search();
                break;
            case LOG:
                App.disp.setCurrent(new LogScreen(this));
                break;
            default:
                App.disp.setCurrent(new TlsTestScreen(this));
                break;
        }
    }

    /** Runs the first page of the search (index 0) with the overlay on this screen. */
    private void search() {
        final SearchState s = SearchState.current;
        if (s.from == null || s.to == null) {
            Alert a = new Alert("Hledat", "Vyberte místo odjezdu (Odkud) a cíl (Kam).", null, AlertType.WARNING);
            a.setTimeout(3000);
            App.disp.disp.setCurrent(a, this);
            return;
        }
        final String when = s.whenString();
        final StartScreen self = this;

        RequestCallback cb = new RequestCallback() {
            public Object request() throws Exception {
                return PubtranApi.search(s, when, 0, new Vector());
            }
            public void onSuccess(Object result) {
                Vector conns = (Vector) result;
                if (conns.size() == 0) {
                    Alert a = new Alert("Hledat", "Žádné spojení nenalezeno.", null, AlertType.INFO);
                    a.setTimeout(3000);
                    App.disp.disp.setCurrent(a, self);
                    return;
                }
                ResultsScreen rs = new ResultsScreen(s, when, conns, self);
                App.disp.setCurrent(rs);
            }
        };
        new RequestThread(cb, this).start();
    }

    /** Called by sub-screens when they return here, so tile captions are up to date. */
    public void refresh() {
        repaint();
    }

    protected void showNotify() {
        repaint();
    }

    // --- Icons -------------------------------------------------------------------

    protected void drawIconShape(Graphics g, int index, int x, int y, int size) {
        switch (index) {
            case FROM: { // a map pin
                int r = size * 2 / 3;
                int px = x + (size - r) / 2;
                g.fillArc(px, y, r, r, 0, 360);
                g.fillTriangle(px + r / 6, y + r * 2 / 3, px + r - r / 6, y + r * 2 / 3, x + size / 2, y + size);
                break;
            }
            case TO: { // a flag
                int pole = x + size / 4;
                g.drawLine(pole, y, pole, y + size);
                g.fillTriangle(pole, y, pole, y + size / 2, x + size - 2, y + size / 4);
                break;
            }
            case VIA: { // three stops on a line
                int cy = y + size / 2;
                g.drawLine(x, cy, x + size, cy);
                int r = Math.max(4, size / 5);
                for (int i = 0; i < 3; i++) {
                    int cx = x + size * i / 2;
                    g.fillArc(cx - r / 2, cy - r / 2, r, r, 0, 360);
                }
                break;
            }
            case WHEN: { // a clock
                g.drawArc(x, y, size, size, 0, 360);
                int cx = x + size / 2;
                int cy = y + size / 2;
                g.drawLine(cx, cy, cx, y + size / 5);
                g.drawLine(cx, cy, x + size * 3 / 4, cy);
                break;
            }
            case SEARCH: { // a bus front
                int bw = size * 3 / 4;
                int bx = x + (size - bw) / 2;
                g.fillRoundRect(bx, y, bw, size * 5 / 6, 6, 6);
                int c = g.getColor();
                g.setColor(shade(c, -120));
                g.fillRect(bx + 3, y + size / 8, bw - 6, size / 3);
                g.fillArc(bx + 3, y + size * 5 / 6 - size / 5, size / 7, size / 7, 0, 360);
                g.fillArc(bx + bw - 3 - size / 7, y + size * 5 / 6 - size / 5, size / 7, size / 7, 0, 360);
                g.setColor(c);
                g.fillRect(bx + 2, y + size * 5 / 6, size / 8, size / 6);
                g.fillRect(bx + bw - 2 - size / 8, y + size * 5 / 6, size / 8, size / 6);
                break;
            }
            case TLS_TEST: { // a padlock
                int bw = size * 2 / 3;
                int bx = x + (size - bw) / 2;
                int by = y + size * 2 / 5;
                g.fillRoundRect(bx, by, bw, size - size * 2 / 5, 4, 4);
                int sw = bw * 2 / 3;
                g.drawArc(x + (size - sw) / 2, y, sw, size * 3 / 5, 0, 180);
                g.drawLine(x + (size - sw) / 2, y + size * 3 / 10, x + (size - sw) / 2, by);
                g.drawLine(x + (size + sw) / 2, y + size * 3 / 10, x + (size + sw) / 2, by);
                break;
            }
            default: { // Log - lines of a list
                g.drawRoundRect(x, y, size, size, 4, 4);
                int gap = Math.max(3, size / 5);
                for (int ly = y + gap; ly < y + size - 2; ly += gap) {
                    g.drawLine(x + size / 6, ly, x + size - size / 6, ly);
                }
                break;
            }
        }
    }

    // --- Loading overlay (LoadingHost) ------------------------------------------

    public void setLoading(boolean value) {
        loading = value;
        if (loading) addCommand(CANCEL_COMMAND);
        else removeCommand(CANCEL_COMMAND);
        repaint();
        if (loading) {
            Thread ticker = new Thread() {
                public void run() {
                    while (loading) {
                        spinnerFrame++;
                        // only the spinner box, not the whole screen: a full repaint (tiles +
                        // stipple) every tick kept the 9300's CPU busy while the request ran
                        int[] b = spinnerBox();
                        repaint(b[0], b[1], b[2], b[3]);
                        try {
                            Thread.sleep(Spinner.TICK_MS);
                        }
                        catch (InterruptedException e) {}
                    }
                }
            };
            ticker.start();
        }
    }

    /** x, y, w, h of the solid box with the spinner and the two text lines. */
    private int[] spinnerBox() {
        int w = getWidth();
        int h = getHeight();
        int size = Math.max(20, Math.min(w, h) / 4);
        int fh = Font.getDefaultFont().getHeight();
        int bw = Math.min(w, Math.max(size + 16, Font.getDefaultFont().stringWidth("Připojování... (getroutesopt, pokus 3)") + 16));
        int top = h / 2 - 6 - size / 2 - 4;
        int bh = size + 2 * fh + 16;
        if (top + bh > h) bh = h - top;
        return new int[] {(w - bw) / 2, top, bw, bh};
    }

    protected void paint(Graphics g) {
        // ticker repaints cover only the spinner box: draw just the box then
        if (loading && g.getClipHeight() < getHeight()) {
            paintSpinnerBox(g);
            return;
        }
        super.paint(g);
    }

    /** Dims the tiles with a stipple (no alpha blending in MIDP2) + spinner + progress. */
    protected void paintOverlay(Graphics g) {
        if (!loading) return;
        int w = getWidth();
        int h = getHeight();

        g.setColor(0x000000);
        for (int yy = 0; yy < h; yy += 2) {
            g.drawLine(0, yy, w - 1, yy);
        }
        paintSpinnerBox(g);
    }

    private void paintSpinnerBox(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        int[] b = spinnerBox();
        g.setColor(BG_COLOR);
        g.fillRect(b[0], b[1], b[2], b[3]);

        int size = Math.min(w, h) / 4;
        if (size < 20) size = 20;
        int cy = h / 2;
        Spinner.draw(g, w / 2, cy - 6, size, spinnerFrame, BUTTON_FOCUS_COLOR, BG_COLOR);

        g.setColor(TEXT_COLOR);
        int ty = cy + size / 2 + 2;
        g.drawString(PubtranApi.progressText(), w / 2, ty, Graphics.TOP | Graphics.HCENTER);
        g.drawString("Zrušit: Esc nebo menu", w / 2, ty + g.getFont().getHeight() + 2, Graphics.TOP | Graphics.HCENTER);
    }

    protected void keyPressed(int keyCode) {
        // Esc (27) / Clear cancels the request in flight
        if (loading && (keyCode == 27 || keyCode == -8)) {
            RequestThread.cancelActive();
            return;
        }
        super.keyPressed(keyCode);
    }

    // --- Commands -----------------------------------------------------------------

    public void commandAction(Command c, Displayable d) {
        if (c == CANCEL_COMMAND) {
            RequestThread.cancelActive();
            return;
        }
        if (loading) return;
        if (c == OPEN_COMMAND) {
            onTileSelected(selected);
        }
        else if (c == SEARCH_COMMAND) {
            search();
        }
        else if (c == SWAP_COMMAND) {
            SearchState.current.swap();
            repaint();
        }
        else if (c == NOW_COMMAND) {
            SearchState.current.useNow = true;
            repaint();
        }
        else if (c == EXIT_COMMAND) {
            App.instance.notifyDestroyed();
        }
        else if (c == INFO_COMMAND) {
            Alert alert = new Alert(
                "Jízdní řády",
                "Vyhledávání spojení přes " + PubtranApi.BASE_URL + " (backend aplikace " +
                "Jízdní řády / Mapy.cz). Protokol FastRPC, bez přihlášení.\n\n" +
                "Odkud / Kam / Přes: psaním se načítají návrhy, bez textu se ukážou " +
                "nedávná místa.\nKdy: datum, čas, odjezd/příjezd a filtry.\n" +
                "Hledat: seznam spojení, dřívější/další spoje, detail spoje a " +
                "předchozí/následující spoj stejné linky.\n\n" +
                "Log: doba, velikost a TLS údaje každého požadavku. Používá se " +
                "jen nativní TLS telefonu (SSLADAPTOR.dll).\n\nHTTPS test: GET na vybrané stránky a co spojení hlásí o TLS.",
                null, AlertType.INFO
            );
            alert.setTimeout(Alert.FOREVER);
            App.disp.disp.setCurrent(alert, this);
        }
    }
}
