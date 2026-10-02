package pubtran;

import com.gtrxac.discord.App;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Base for the hand-drawn list screens (results, connection detail, trip): a vertical,
 * scrolling list of rows (cards), some of them focusable, in the start screen's dark
 * theme (Theme).
 *
 * Keys: Up/Down move the focus between focusable rows; a row taller than the screen
 * is scrolled through first. Enter / select opens the focused row. Left/Right go to
 * onLeft()/onRight() (paging). A tap selects a row, a drag scrolls.
 *
 * It is also a LoadingHost: RequestThread shows the spinner box over it, repainting
 * only that box (never the whole screen in a loop - see LESSONS_NOKIA_9300.md).
 */
public abstract class CardCanvas extends Canvas implements LoadingHost {
    /** One row of the list. */
    public abstract static class Row {
        public boolean focusable;

        public abstract int height(int width);

        /** Paints the row at x, y (full width w); focus = the row has the focus. */
        public abstract void paint(Graphics g, int x, int y, int w, boolean focus);

        /** Enter / tap on a focusable row. */
        public void select() {}
    }

    protected static final int PAD = 4;
    private static final Command CANCEL_COMMAND = new Command("Zrušit", Command.STOP, 0);

    protected Vector rows = new Vector();
    private int[] tops = new int[0];
    private int total;
    private int layoutWidth = -1;
    protected int focus = -1;
    private int scroll;

    private int pressY = -1, pressScroll;
    private boolean dragged;

    private volatile boolean loading;
    private int spinnerFrame;

    /** Replaces the rows; focusRow = index of the row to focus (or -1 for the first focusable). */
    protected synchronized void setRows(Vector newRows, int focusRow) {
        rows = newRows;
        layoutWidth = -1;
        focus = -1;
        if (focusRow >= 0 && focusRow < rows.size() && row(focusRow).focusable) focus = focusRow;
        if (focus < 0) focus = nextFocusable(-1, 1);
        scroll = 0;
        layout();
        if (focus >= 0) ensureVisible(focus, true);
        repaint();
    }

    /** Scrolls so that the point `offset` px below the top of row i is near the top (after setRows). */
    protected synchronized void scrollToRow(int i, int offset) {
        layout();
        if (i < 0 || i >= rows.size()) return;
        scroll = Math.max(0, Math.min(maxScroll(), tops[i] + offset - getHeight() / 4));
        repaint();
    }

    protected Row row(int i) {
        return (Row) rows.elementAt(i);
    }

    private void layout() {
        int w = getWidth();
        if (w == layoutWidth && tops.length == rows.size()) return;
        layoutWidth = w;
        tops = new int[rows.size()];
        int y = PAD;
        for (int i = 0; i < rows.size(); i++) {
            tops[i] = y;
            y += row(i).height(w - 2 * PAD) + PAD;
        }
        total = y;
    }

    private int maxScroll() {
        return Math.max(0, total - getHeight());
    }

    private int nextFocusable(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) {
            if (row(i).focusable) return i;
        }
        return -1;
    }

    private void ensureVisible(int i, boolean alignTop) {
        int top = tops[i] - PAD;
        int bottom = tops[i] + row(i).height(getWidth() - 2 * PAD) + PAD;
        int h = getHeight();
        if (alignTop || bottom - top > h) {
            if (top < scroll || bottom > scroll + h) scroll = top;
        }
        else if (top < scroll) scroll = top;
        else if (bottom > scroll + h) scroll = bottom - h;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    protected void paint(Graphics g) {
        layout();
        int w = getWidth();
        int h = getHeight();
        if (loading && g.getClipHeight() < h) { // ticker repaint: only the spinner box
            paintSpinnerBox(g);
            return;
        }
        g.setColor(Theme.BG);
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < rows.size(); i++) {
            int y = tops[i] - scroll;
            int rh = row(i).height(w - 2 * PAD);
            if (y + rh < 0 || y > h) continue;
            row(i).paint(g, PAD, y, w - 2 * PAD, i == focus && !loading);
            g.setClip(0, 0, w, h);
        }
        // scroll bar
        if (total > h) {
            int barH = Math.max(10, h * h / total);
            int barY = (h - barH) * scroll / Math.max(1, maxScroll());
            g.setColor(Theme.SEPARATOR);
            g.fillRect(w - 3, barY, 2, barH);
        }
        if (loading) {
            g.setColor(0x000000);
            for (int yy = 0; yy < h; yy += 2) g.drawLine(0, yy, w - 1, yy);
            paintSpinnerBox(g);
        }
    }

    // ---------------------------------------------------------------- input

    protected void keyPressed(int keyCode) {
        if (loading) {
            if (keyCode == 27 || keyCode == -8) RequestThread.cancelActive();
            return;
        }
        if (keyCode == 10 || keyCode == 13) {
            activate();
            return;
        }
        int action;
        try {
            action = getGameAction(keyCode);
        }
        catch (Exception e) {
            return;
        }
        if (action == UP) move(-1);
        else if (action == DOWN) move(1);
        else if (action == FIRE) activate();
        else if (action == LEFT) onLeft();
        else if (action == RIGHT) onRight();
    }

    protected void keyRepeated(int keyCode) {
        if (loading) return;
        int action;
        try {
            action = getGameAction(keyCode);
        }
        catch (Exception e) {
            return;
        }
        if (action == UP) move(-1);
        else if (action == DOWN) move(1);
    }

    private synchronized void move(int dir) {
        layout();
        int h = getHeight();
        int step = Math.max(Theme.PLAIN.getHeight() * 2, h / 3);
        if (focus >= 0) {
            int top = tops[focus];
            int bottom = top + row(focus).height(getWidth() - 2 * PAD);
            // inside a tall focused row: scroll through it first
            if (dir > 0 && bottom > scroll + h) {
                scroll = Math.min(maxScroll(), Math.min(scroll + step, bottom + PAD - h));
                repaint();
                return;
            }
            if (dir < 0 && top < scroll) {
                scroll = Math.max(0, Math.max(scroll - step, top - PAD));
                repaint();
                return;
            }
        }
        int next = nextFocusable(focus, dir);
        if (next >= 0) {
            // a far-away next row: scroll towards it first, so non-focusable rows can be read
            int nTop = tops[next];
            if (dir > 0 && nTop > scroll + h + step && scroll < maxScroll()) {
                scroll = Math.min(maxScroll(), scroll + step);
            }
            else if (dir < 0 && nTop + row(next).height(getWidth() - 2 * PAD) < scroll - step && scroll > 0) {
                scroll = Math.max(0, scroll - step);
            }
            else {
                focus = next;
                ensureVisible(focus, false);
            }
        }
        else {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + dir * step));
        }
        repaint();
    }

    private void activate() {
        if (focus >= 0 && focus < rows.size()) row(focus).select();
    }

    protected void onLeft() {}

    protected void onRight() {}

    protected void pointerPressed(int x, int y) {
        if (loading) return;
        pressY = y;
        pressScroll = scroll;
        dragged = false;
    }

    protected void pointerDragged(int x, int y) {
        if (pressY < 0) return;
        int dy = y - pressY;
        if (!dragged && Math.abs(dy) < 8) return;
        dragged = true;
        scroll = Math.max(0, Math.min(maxScroll(), pressScroll - dy));
        repaint();
    }

    protected void pointerReleased(int x, int y) {
        if (pressY < 0) return;
        pressY = -1;
        if (dragged) return;
        int yy = y + scroll;
        for (int i = 0; i < rows.size(); i++) {
            int top = tops[i];
            if (yy >= top && yy < top + row(i).height(getWidth() - 2 * PAD)) {
                if (row(i).focusable) {
                    focus = i;
                    repaint();
                    row(i).select();
                }
                return;
            }
        }
    }

    protected void sizeChanged(int w, int h) {
        layoutWidth = -1;
        repaint();
    }

    // ---------------------------------------------------------------- loading (LoadingHost)

    public void setLoading(boolean value) {
        loading = value;
        if (loading) addCommand(CANCEL_COMMAND);
        else removeCommand(CANCEL_COMMAND);
        repaint();
        if (loading) {
            new Thread() {
                public void run() {
                    while (loading) {
                        spinnerFrame++;
                        int[] b = spinnerBox();
                        repaint(b[0], b[1], b[2], b[3]);
                        try { Thread.sleep(Spinner.TICK_MS); } catch (InterruptedException e) {}
                    }
                }
            }.start();
        }
    }

    /** Call first in commandAction: handles "Zrušit" during loading. Returns true if handled. */
    protected boolean handleCommand(Command c) {
        if (c == CANCEL_COMMAND) {
            RequestThread.cancelActive();
            return true;
        }
        return loading; // ignore other commands while loading
    }

    private int[] spinnerBox() {
        int w = getWidth();
        int h = getHeight();
        int size = Math.max(20, Math.min(w, h) / 4);
        int fh = Theme.PLAIN.getHeight();
        int bw = Math.min(w, Theme.PLAIN.stringWidth("Připojování... (getroutesopt, pokus 3)") + 16);
        int top = Math.max(0, h / 2 - 6 - size / 2 - 4);
        int bh = Math.min(h - top, size + 2 * fh + 16);
        return new int[] {(w - bw) / 2, top, bw, bh};
    }

    private void paintSpinnerBox(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        int[] b = spinnerBox();
        g.setColor(Theme.BG);
        g.fillRect(b[0], b[1], b[2], b[3]);
        int size = Math.max(20, Math.min(w, h) / 4);
        int cy = h / 2;
        Spinner.draw(g, w / 2, cy - 6, size, spinnerFrame, Theme.CARD_FOCUS_BORDER, Theme.BG);
        g.setColor(Theme.TEXT);
        g.setFont(Theme.PLAIN);
        int ty = cy + size / 2 + 2;
        g.drawString(PubtranApi.progressText(), w / 2, ty, Graphics.TOP | Graphics.HCENTER);
        g.setColor(Theme.TEXT_DIM);
        g.drawString("Zrušit: Esc nebo menu", w / 2, ty + Theme.PLAIN.getHeight() + 2, Graphics.TOP | Graphics.HCENTER);
    }

    // ---------------------------------------------------------------- common row types

    /** Card background (rounded) with the focus border. */
    public static void card(Graphics g, int x, int y, int w, int h, boolean focus) {
        g.setColor(Theme.CARD);
        g.fillRoundRect(x, y, w, h, 8, 8);
        if (focus) {
            g.setColor(Theme.CARD_FOCUS_BORDER);
            g.drawRoundRect(x, y, w - 1, h - 1, 8, 8);
            g.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 6, 6);
        }
    }

    /** A focusable button-like row ("<< Dřívější spoje"). */
    public static class ButtonRow extends Row {
        private final String text;
        private final Runnable action;

        public ButtonRow(String text, Runnable action) {
            this.text = text;
            this.action = action;
            focusable = true;
        }

        public int height(int w) {
            return Theme.PLAIN.getHeight() + 6;
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            int h = height(w);
            g.setColor(focus ? Theme.CARD_FOCUS_BORDER : Theme.HEADER);
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(focus ? Theme.TEXT : Theme.TEXT_DIM);
            g.setFont(Theme.PLAIN);
            g.drawString(text, x + w / 2, y + 3, Graphics.TOP | Graphics.HCENTER);
        }

        public void select() {
            if (action != null) action.run();
        }
    }

    /** Plain wrapped text (small, dim or coloured), not focusable. */
    public static class TextRow extends Row {
        private final String text;
        private final Font font;
        private final int color;
        private Vector lines;
        private int lw = -1;

        public TextRow(String text, Font font, int color) {
            this.text = text;
            this.font = font;
            this.color = color;
        }

        private Vector lines(int w) {
            if (lines == null || lw != w) {
                lines = Theme.wrap(text, font, w - 8);
                lw = w;
            }
            return lines;
        }

        public int height(int w) {
            return lines(w).size() * font.getHeight();
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            Vector l = lines(w);
            g.setFont(font);
            g.setColor(color);
            for (int i = 0; i < l.size(); i++) {
                g.drawString((String) l.elementAt(i), x + 4, y + i * font.getHeight(), Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    /** Day separator: "so 4. 10." with a line. */
    public static class SeparatorRow extends Row {
        private final String text;

        public SeparatorRow(String text) {
            this.text = text;
        }

        public int height(int w) {
            return Theme.SMALL_BOLD.getHeight();
        }

        public void paint(Graphics g, int x, int y, int w, boolean focus) {
            g.setFont(Theme.SMALL_BOLD);
            g.setColor(Theme.TEXT_DIM);
            g.drawString(text, x + 4, y, Graphics.TOP | Graphics.LEFT);
            int lx = x + 12 + Theme.SMALL_BOLD.stringWidth(text);
            int ly = y + Theme.SMALL_BOLD.getHeight() / 2;
            g.setColor(Theme.SEPARATOR);
            g.drawLine(lx, ly, x + w - 4, ly);
        }
    }

    /** Back to the previous screen helper. */
    protected static void show(Displayable d) {
        App.disp.setCurrent(d);
    }
}
