package pubtran;


import javax.microedition.lcdui.*;

/**
 * Shared base for this fork's pointer-first tile pages (StartScreen):
 * a grid of hand-drawn 3D buttons, laid out to fit whatever screen size the Canvas gets.
 * Subclasses only say how many tiles there are, what each one says and looks like, and
 * what happens when one is picked - layout, input and drawing all live here.
 *
 * Series 80 Communicators (Nokia 9210/9300/9500) are pointer-driven, not D-pad-driven:
 * the navi-wheel moves an on-screen mouse cursor, and there's no "left/right action key"
 * like on a typical feature phone. So pointerPressed()/pointerDragged() are the PRIMARY
 * input (tap a tile to open it, drag across to move the highlight); keyPressed() with
 * getGameAction() is only a secondary fallback for devices/emulators without a pointer.
 *
 * There's no image assets or 3D API here (plain javax.microedition.lcdui), so the
 * buttons are genuine extruded polygons built by hand: a flat front face plus a right
 * face and a bottom face, each a parallelogram made of two fillTriangle() calls, shaded
 * darker than the front so the button reads as a solid block. The focused tile uses a
 * shallow depth and a darker front face, so it reads as pressed in.
 */
public abstract class TileScreen extends Canvas {
    protected static final int BG_COLOR = 0x2B2D31;
    protected static final int BUTTON_COLOR = 0x383A40;
    protected static final int BUTTON_FOCUS_COLOR = 0x5865F2;
    protected static final int TEXT_COLOR = 0xFFFFFF;
    protected static final int ICON_COLOR = 0xDBDEE1;
    protected static final int ICON_FOCUS_COLOR = 0xFFFFFF;

    // How far the extruded side/bottom faces reach beyond the front face, in pixels.
    // CELL_PAD has to leave at least this much room around each button, or the
    // extrusion of one button would be drawn over its neighbor.
    private static final int DEPTH = 6;
    private static final int CELL_PAD = 8;

    // Grid geometry, recomputed from getWidth()/getHeight() by layout() before every
    // paint and every pointer/key hit-test, so painting and hit-testing never drift apart.
    private int cols, rows, cellW, cellH;

    // The currently highlighted tile: set on pointerDragged() (hover) or by keyboard
    // navigation, and opened on pointerPressed() (tap) or FIRE.
    protected int selected = 0;

    // --- What subclasses provide ------------------------------------------------------

    protected abstract int getTileCount();

    /** The tile's caption; may contain '\n' for a multi-line caption. */
    protected abstract String getTileLabel(int index);

    /** Draws the tile's icon in the current color, inside a size x size box at (x, y). */
    protected abstract void drawIconShape(Graphics g, int index, int x, int y, int size);

    /** Called when a tile is tapped, or picked with FIRE. */
    protected abstract void onTileSelected(int index);

    /** Hook: fixed column count, or 0 to pick the most square-ish grid automatically. */
    protected int getPreferredColumns() {
        return 0;
    }

    /** Hook: return true to ignore input, e.g. while a request is loading. */
    protected boolean isInputBlocked() {
        return false;
    }

    /** Hook: drawn on top of the tiles, e.g. a loading overlay. */
    protected void paintOverlay(Graphics g) {
    }

    /** Hook: front-face color of a tile that isn't focused, e.g. to mark the active choice. */
    protected int getTileColor(int index) {
        return BUTTON_COLOR;
    }

    // --- Layout --------------------------------------------------------------------

    private void layout() {
        int w = getWidth();
        int h = getHeight();
        int n = getTileCount();
        if (w <= 0 || h <= 0) {
            cols = n;
            rows = 1;
        }
        else if (getPreferredColumns() > 0) {
            cols = Math.min(getPreferredColumns(), n);
            rows = (n + cols - 1) / cols;
        }
        else {
            // Pick the column count whose cells come out closest to square.
            int bestCols = 1;
            int bestDiff = Integer.MAX_VALUE;
            for (int c = 1; c <= n; c++) {
                int r = (n + c - 1) / c;
                int cw = w / c;
                int ch = h / r;
                int diff = (cw > ch) ? (cw - ch) : (ch - cw);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestCols = c;
                }
            }
            cols = bestCols;
            rows = (n + cols - 1) / cols;
        }
        cellW = w / cols;
        cellH = h / rows;
    }

    /** Returns the tile index at (x, y), or -1 if there isn't one there. */
    private int cellAt(int x, int y) {
        if (cellW <= 0 || cellH <= 0 || x < 0 || y < 0) return -1;
        int col = x / cellW;
        int row = y / cellH;
        if (col < 0 || col >= cols || row < 0 || row >= rows) return -1;
        int idx = row * cols + col;
        return (idx >= 0 && idx < getTileCount()) ? idx : -1;
    }

    // --- Pointer input (primary) ---------------------------------------------------

    public void pointerPressed(int x, int y) {
        if (isInputBlocked()) return;
        layout();
        int idx = cellAt(x, y);
        if (idx >= 0) {
            // Not calling serviceRepaints() here: it throws IllegalStateException when
            // invoked from the event thread, which is exactly where pointerPressed() runs.
            selected = idx;
            repaint();
            onTileSelected(idx);
        }
    }

    public void pointerDragged(int x, int y) {
        if (isInputBlocked()) return;
        layout();
        int idx = cellAt(x, y);
        if (idx >= 0 && idx != selected) {
            selected = idx;
            repaint();
        }
    }

    // --- Keyboard input (secondary fallback) ----------------------------------------

    protected void keyPressed(int keyCode) {
        if (isInputBlocked()) return;

        int action;
        try {
            action = getGameAction(keyCode);
        }
        catch (IllegalArgumentException e) {
            return;
        }

        layout();
        int col = selected % cols;
        int row = selected / cols;

        if (action == RIGHT) moveSelection(col + 1, row);
        else if (action == LEFT) moveSelection(col - 1, row);
        else if (action == DOWN) moveSelection(col, row + 1);
        else if (action == UP) moveSelection(col, row - 1);
        else if (action == FIRE) onTileSelected(selected);
    }

    private void moveSelection(int col, int row) {
        if (col < 0) col = cols - 1;
        if (col >= cols) col = 0;
        if (row < 0) row = rows - 1;
        if (row >= rows) row = 0;

        int idx = row * cols + col;
        if (idx >= getTileCount()) idx = getTileCount() - 1;
        selected = idx;
        repaint();
    }

    // --- Drawing ----------------------------------------------------------------

    protected void paint(Graphics g) {
        layout();

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, getWidth(), getHeight());

        int n = getTileCount();
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            drawButton(g, i, col * cellW, row * cellH, cellW, cellH);
        }

        paintOverlay(g);
    }

    private void drawButton(Graphics g, int index, int x, int y, int w, int h) {
        boolean focused = (index == selected);
        int bx = x + CELL_PAD;
        int by = y + CELL_PAD;
        int bw = w - CELL_PAD * 2;
        int bh = h - CELL_PAD * 2;
        if (bw <= 0 || bh <= 0) return;

        int baseColor = focused ? BUTTON_FOCUS_COLOR : getTileColor(index);
        draw3DPanel(g, bx, by, bw, bh, baseColor, focused);

        // Icon in the upper part of the button, caption lines along the bottom. A pressed
        // (focused) button's contents sink down-right by 1px, matching its shallower
        // extrusion.
        int sink = focused ? 1 : 0;

        String[] lines = splitLines(getTileLabel(index));
        Font font = g.getFont();
        int lineH = font.getHeight();
        int labelH = lineH * lines.length + 4;
        int iconAreaH = bh - labelH;

        // Keep captions inside their own button, so a long line can't spill onto a
        // neighbor on a narrow screen.
        g.setClip(bx, by, bw, bh);

        if (iconAreaH >= 10) {
            int iconSize = Math.min(bw - 8, iconAreaH - 4);
            if (iconSize < 6) iconSize = 6;
            int iconX = bx + (bw - iconSize) / 2 + sink;
            int iconY = by + (iconAreaH - iconSize) / 2 + sink;
            int iconColor = focused ? ICON_FOCUS_COLOR : ICON_COLOR;
            drawIcon3D(g, index, iconX, iconY, iconSize, iconColor);
        }
        else {
            iconAreaH = Math.max(0, (bh - lineH * lines.length) / 2);
        }

        int labelX = bx + bw / 2 + sink;
        int labelY = by + iconAreaH + sink;
        for (int i = 0; i < lines.length; i++) {
            int ly = labelY + i * lineH;
            g.setColor(shade(TEXT_COLOR, -130));
            g.drawString(lines[i], labelX + 1, ly + 1, Graphics.TOP | Graphics.HCENTER);
            g.setColor(TEXT_COLOR);
            g.drawString(lines[i], labelX, ly, Graphics.TOP | Graphics.HCENTER);
        }

        g.setClip(0, 0, getWidth(), getHeight());
    }

    /**
     * Draws one button as an extruded 3D box: a flat front face, plus a right face and a
     * bottom face, each a parallelogram built from two fillTriangle() calls, shaded
     * progressively darker than the front. Light comes from the upper-left, so the
     * extrusion falls to the lower-right - the same direction as the icon/label emboss.
     */
    private void draw3DPanel(Graphics g, int x, int y, int w, int h, int base, boolean pressed) {
        int depth = pressed ? 2 : DEPTH;
        int frontColor = pressed ? shade(base, -20) : base;
        int rightColor = shade(base, pressed ? -40 : -90);
        int bottomColor = shade(base, pressed ? -55 : -120);

        // Right face: the front face's right edge, pushed (depth, depth) back.
        g.setColor(rightColor);
        g.fillTriangle(x + w, y, x + w + depth, y + depth, x + w, y + h);
        g.fillTriangle(x + w + depth, y + depth, x + w + depth, y + h + depth, x + w, y + h);

        // Bottom face: same idea, off the front face's bottom edge.
        g.setColor(bottomColor);
        g.fillTriangle(x, y + h, x + depth, y + h + depth, x + w, y + h);
        g.fillTriangle(x + depth, y + h + depth, x + w + depth, y + h + depth, x + w, y + h);

        // Front face, drawn last so it covers the seams where the other faces meet it.
        g.setColor(frontColor);
        g.fillRect(x, y, w, h);

        g.setColor(shade(frontColor, 40));
        g.drawLine(x, y, x + w, y);
        g.drawLine(x, y, x, y + h);
    }

    /**
     * Draws a tile's icon as a small emboss: a dark copy offset down-right, a light copy
     * offset up-left, then the base-colored icon on top.
     */
    private void drawIcon3D(Graphics g, int index, int x, int y, int size, int baseColor) {
        g.setColor(shade(baseColor, -70));
        drawIconShape(g, index, x + 1, y + 1, size);

        g.setColor(shade(baseColor, 70));
        drawIconShape(g, index, x - 1, y - 1, size);

        g.setColor(baseColor);
        drawIconShape(g, index, x, y, size);
    }

    /** Splits s on '\n' - CLDC's String has no split(). */
    private static String[] splitLines(String s) {
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        String[] out = new String[count];
        int start = 0;
        int k = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                out[k++] = s.substring(start, i);
                start = i + 1;
            }
        }
        return out;
    }

    /** Shifts each RGB channel of color by delta (positive lightens, negative darkens). */
    protected static int shade(int rgb, int delta) {
        int r = clampByte(((rgb >> 16) & 0xFF) + delta);
        int g = clampByte(((rgb >> 8) & 0xFF) + delta);
        int b = clampByte((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}
