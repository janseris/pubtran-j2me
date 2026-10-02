package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Odkud / Kam / Přes picker, drawn by hand on a Canvas (same colours as the tiles).
 *
 * Why a Canvas and not a Form: in a native Form the first click on an item only
 * focuses it and a second one activates it. Here a single click on a row - or Enter /
 * the select key on the highlighted row - picks the place immediately, and rows are
 * left-aligned like a list.
 *
 *   [ Hledat: olomo_                                   ]
 *   Návrhy (8, 320 ms)
 *   * Olomouc   Statutární město, Česko
 *   * Olomouc město   Vlaková stanice, ...
 *
 * Typing on the Communicator's keyboard goes straight into the search field; Enter
 * (or "Hledat") sends ONE suggest request - nothing is sent while typing, because on
 * the 9300 each request is a full TLS handshake that ties up the phone. Clicking the field, or the
 * "Upravit text" command, opens the phone's native text editor instead (for accents,
 * and for emulators that don't pass letter keys to a Canvas). With an empty field the
 * recently used places are listed.
 *
 * Keys: Up/Down move the highlight, Enter/select picks it (on the field: picks the
 * first suggestion, or opens the editor when there's none), Backspace deletes.
 */
public class PlaceScreen extends Canvas implements CommandListener {
    private static final Command PICK_COMMAND = new Command("Vybrat", Command.OK, 1);
    private static final Command EDIT_COMMAND = new Command("Upravit text", Command.SCREEN, 2);
    private static final Command SEARCH_COMMAND = new Command("Hledat", Command.SCREEN, 3);
    private static final Command CLEAR_COMMAND = new Command("Bez zastávky Přes", Command.SCREEN, 4);
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 5);

    private static final Command TB_OK = new Command("Hledat", Command.OK, 1);
    private static final Command TB_CANCEL = new Command("Zpět", Command.BACK, 2);

    private static final int BG = 0x2B2D31;
    private static final int FIELD_BG = 0x1E1F22;
    private static final int FIELD_BORDER = 0x4E5058;
    private static final int ROW_FOCUS = 0x5865F2;
    private static final int TEXT = 0xFFFFFF;
    private static final int TEXT_DIM = 0xB5BAC1;
    private static final int HINT = 0x80848E;

    private final int which;
    private final StartScreen back;
    private TextBox editor;

    private String query = "";
    private Vector places = new Vector();
    private String status = "";

    /** -1 = the search field, 0..n-1 = a place row. */
    private int selected = -1;
    /** Index of the first place row drawn (scrolling). */
    private int scroll = 0;

    // Pointer: pick on release, unless the pointer moved (then it was a drag-scroll).
    private int pressY = -1;
    private int pressScroll;
    private boolean dragged;

    /** Incremented on every edit - a background search only applies its result if it's still the latest. */
    private int generation = 0;

    private final Font fontBold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private final Font fontPlain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    private final Font fontSmall = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

    public PlaceScreen(int which, StartScreen back) {
        this.which = which;
        this.back = back;
        setTitle(which == StartScreen.FROM ? "Odkud" : which == StartScreen.TO ? "Kam" : "Přes");

        addCommand(PICK_COMMAND);
        addCommand(EDIT_COMMAND);
        addCommand(SEARCH_COMMAND);
        if (which == StartScreen.VIA) addCommand(CLEAR_COMMAND);
        addCommand(BACK_COMMAND);
        setCommandListener(this);

        showRecent();
    }

    // ============================== data ==============================

    private void showRecent() {
        generation++;
        places = RecentPlaces.load();
        status = places.size() == 0 ? "Napište název obce, zastávky nebo adresy." : "Nedávná místa";
        selected = places.size() > 0 ? 0 : -1;
        scroll = 0;
        repaint();
    }

    /** The query the shown suggestions belong to (null = recent places / nothing searched). */
    private String lastSearched;
    /** A suggest request is in flight - don't start another one. */
    private volatile boolean busy;

    /**
     * Typing only edits the text - nothing is sent. On the 9300 every request is a new
     * connection with a full TLS handshake (the EKA1 TLS patch has no session
     * resumption), and firing one per typing pause made the phone unresponsive.
     */
    private void queryEdited() {
        generation++; // results on screen no longer match the text
        if (query.trim().length() == 0) {
            lastSearched = null;
            showRecent();
            return;
        }
        status = "Enter = hledat";
        repaint();
    }

    /** Sends one suggest request for the current text (Enter / "Hledat" / editor OK). */
    private void searchNow() {
        final String q = query.trim();
        if (q.length() == 0) {
            showRecent();
            return;
        }
        if (busy) return; // one request at a time
        busy = true;
        final int gen = ++generation;
        lastSearched = q;
        new Thread() {
            public void run() {
                try {
                    runSuggest(q, gen);
                }
                finally {
                    busy = false;
                }
            }
        }.start();
    }

    /** Runs on a background thread. */
    private void runSuggest(String q, int gen) {
        status = "Hledám \"" + q + "\"...";
        repaint();
        SearchState s = SearchState.current;
        // Rank around the other end of the search if known (the app uses the phone's GPS).
        Place bias = (which == StartScreen.FROM) ? s.to : s.from;
        try {
            Vector found = PubtranApi.suggest(q, bias == null ? null : bias.lat, bias == null ? null : bias.lon);
            if (gen != generation) return;
            places = found;
            scroll = 0;
            if (selected >= places.size()) selected = places.size() - 1;
            status = found.size() == 0
                ? "Nic nenalezeno."
                : "Návrhy (" + found.size() + ", " + PubtranApi.lastRequestDurationMs + " ms) - klikněte na místo";
        }
        catch (Exception e) {
            if (gen == generation) status = "Chyba: " + e.toString();
        }
        repaint();
    }

    private void pick(int index) {
        if (index < 0 || index >= places.size()) return;
        Place p = (Place) places.elementAt(index);
        SearchState s = SearchState.current;
        if (which == StartScreen.FROM) s.from = p;
        else if (which == StartScreen.TO) s.to = p;
        else s.via = p;
        RecentPlaces.add(p);
        goBack();
    }

    private void goBack() {
        generation++; // drop any late suggest result
        back.refresh();
        App.disp.setCurrent(back);
    }

    /** Opens the phone's native text editor for the query (accents, predictive input...). */
    private void openEditor() {
        editor = new TextBox(getTitle(), query, 64, TextField.ANY);
        editor.addCommand(TB_OK);
        editor.addCommand(TB_CANCEL);
        editor.setCommandListener(this);
        App.disp.setCurrent(editor);
    }

    // ============================== layout ==============================

    private int fieldH() {
        return fontPlain.getHeight() + 8;
    }

    private int statusY() {
        return fieldH() + 3;
    }

    private int rowsTop() {
        return statusY() + fontSmall.getHeight() + 3;
    }

    private int rowH() {
        return fontBold.getHeight() + 6;
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - rowsTop()) / rowH());
    }

    private void ensureVisible() {
        if (selected < 0) {
            scroll = 0;
            return;
        }
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visibleRows()) scroll = selected - visibleRows() + 1;
    }

    /** Place index at y, -1 for the search field, -2 for nothing. */
    private int hit(int y) {
        if (y < fieldH()) return -1;
        if (y < rowsTop()) return -2;
        int idx = scroll + (y - rowsTop()) / rowH();
        return idx < places.size() ? idx : -2;
    }

    // ============================== painting ==============================

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        // search field
        int fh = fieldH();
        g.setColor(FIELD_BG);
        g.fillRect(2, 2, w - 4, fh - 4);
        g.setColor(selected == -1 ? ROW_FOCUS : FIELD_BORDER);
        g.drawRect(2, 2, w - 5, fh - 5);
        g.setFont(fontPlain);
        String label = "Hledat: ";
        g.setColor(TEXT_DIM);
        g.drawString(label, 8, 6, Graphics.TOP | Graphics.LEFT);
        int tx = 8 + fontPlain.stringWidth(label);
        if (query.length() == 0) {
            g.setColor(HINT);
            g.drawString("pište a stiskněte Enter, nebo sem klikněte", tx, 6, Graphics.TOP | Graphics.LEFT);
        }
        else {
            g.setColor(TEXT);
            g.drawString(query + "_", tx, 6, Graphics.TOP | Graphics.LEFT);
        }

        // status line (+ live transfer progress while suggest is running)
        g.setFont(fontSmall);
        g.setColor(TEXT_DIM);
        String st = status;
        if (st.startsWith("Hledám")) st = st + "  " + PubtranApi.progressText();
        g.drawString(st, 8, statusY(), Graphics.TOP | Graphics.LEFT);

        // rows - left-aligned: dot, bold name, dimmed description
        int top = rowsTop();
        int rh = rowH();
        int n = visibleRows();
        for (int i = 0; i < n && scroll + i < places.size(); i++) {
            int idx = scroll + i;
            Place p = (Place) places.elementAt(idx);
            int y = top + i * rh;
            boolean focus = idx == selected;
            if (focus) {
                g.setColor(ROW_FOCUS);
                g.fillRect(2, y, w - 4, rh - 2);
            }
            g.setClip(2, y, w - 4, rh - 2);

            int x = 8;
            int dot = Math.max(6, rh / 3);
            g.setColor(p.source.equals("muni") ? 0x4E9BE0 : p.source.equals("pubt") ? 0x3BA55C : 0x949BA4);
            g.fillArc(x, y + (rh - 2 - dot) / 2, dot, dot, 0, 360);
            x += dot + 6;

            g.setFont(fontBold);
            g.setColor(TEXT);
            g.drawString(p.name, x, y + 3, Graphics.TOP | Graphics.LEFT);
            x += fontBold.stringWidth(p.name) + 12;

            String second = p.description.length() > 0 ? p.description : p.kind();
            g.setFont(fontPlain);
            g.setColor(focus ? 0xE3E5E8 : TEXT_DIM);
            g.drawString(second, x, y + 3, Graphics.TOP | Graphics.LEFT);

            g.setClip(0, 0, w, h);
        }

        // position hint when the list doesn't fit
        if (places.size() > n) {
            g.setColor(TEXT_DIM);
            g.setFont(fontSmall);
            String more = (scroll + 1) + "-" + Math.min(places.size(), scroll + n) + " / " + places.size();
            g.drawString(more, w - 6, statusY(), Graphics.TOP | Graphics.RIGHT);
        }
    }

    // ============================== input ==============================

    protected void keyPressed(int keyCode) {
        handleKey(keyCode, false);
    }

    protected void keyRepeated(int keyCode) {
        handleKey(keyCode, true);
    }

    private void handleKey(int keyCode, boolean repeat) {
        // Enter
        if (keyCode == 10 || keyCode == 13) {
            if (!repeat) activate();
            return;
        }
        // Backspace / Clear
        if (keyCode == 8 || keyCode == -8 || keyCode == 127) {
            if (query.length() > 0) {
                query = query.substring(0, query.length() - 1);
                selected = -1;
                scroll = 0;
                queryEdited();
            }
            return;
        }
        // Printable character from the QWERTY keyboard (arrows/softkeys have negative codes)
        if (keyCode >= 32) {
            if (query.length() < 64) {
                query = query + (char) keyCode;
                selected = -1;
                scroll = 0;
                queryEdited();
            }
            return;
        }

        int action;
        try {
            action = getGameAction(keyCode);
        }
        catch (Exception e) {
            return;
        }
        if (action == UP) {
            if (selected > -1) selected--;
            ensureVisible();
            repaint();
        }
        else if (action == DOWN) {
            if (selected < places.size() - 1) selected++;
            ensureVisible();
            repaint();
        }
        else if (action == FIRE && !repeat) {
            activate();
        }
    }

    /**
     * Enter / select / "Vybrat": on a row, pick it. On the field: search if the text
     * changed since the last search, else pick the first suggestion, else open the editor.
     */
    private void activate() {
        String q = query.trim();
        if (selected >= 0) pick(selected);
        else if (q.length() > 0 && !q.equals(lastSearched)) searchNow();
        else if (places.size() > 0 && q.length() > 0) pick(0);
        else openEditor();
    }

    protected void pointerPressed(int x, int y) {
        pressY = y;
        pressScroll = scroll;
        dragged = false;
        int idx = hit(y);
        if (idx >= -1) {
            selected = idx;
            repaint();
        }
    }

    protected void pointerDragged(int x, int y) {
        if (pressY < 0) return;
        int dy = y - pressY;
        if (!dragged && Math.abs(dy) < 8) return;
        dragged = true;
        int maxScroll = Math.max(0, places.size() - visibleRows());
        int s = pressScroll - dy / rowH();
        scroll = Math.max(0, Math.min(maxScroll, s));
        repaint();
    }

    /** One click (press + release on the same row, no dragging) picks the row / edits the field. */
    protected void pointerReleased(int x, int y) {
        if (pressY < 0) return;
        int startIdx = hit(pressY);
        pressY = -1;
        if (dragged) return;
        int idx = hit(y);
        if (idx != startIdx) return;
        if (idx == -1) openEditor();
        else if (idx >= 0) pick(idx);
    }

    // ============================== commands ==============================

    public void commandAction(Command c, Displayable d) {
        if (d == editor) {
            if (c == TB_OK) {
                query = editor.getString();
                selected = -1;
                scroll = 0;
                searchNow();
            }
            editor = null;
            App.disp.setCurrent(this);
            return;
        }
        if (c == PICK_COMMAND) {
            activate();
        }
        else if (c == EDIT_COMMAND) {
            openEditor();
        }
        else if (c == SEARCH_COMMAND) {
            if (query.trim().length() == 0) openEditor();
            else searchNow();
        }
        else if (c == CLEAR_COMMAND) {
            SearchState.current.via = null;
            goBack();
        }
        else if (c == BACK_COMMAND) {
            goBack();
        }
    }
}
