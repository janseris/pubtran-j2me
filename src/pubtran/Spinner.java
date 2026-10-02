package pubtran;


import javax.microedition.lcdui.*;

/**
 * A small rotating "ring with a gap" loading spinner, drawn with two fillArc() calls -
 * no images, and no trig on our side (CLDC's java.lang.Math has no sin/cos - Graphics's
 * own arc drawing handles that internally).
 *
 * Shared by StartScreen (draws it as an inline overlay on top of its own button
 * grid) and LoadingScreen (a full-screen fallback for the List/Form-based JP
 * screens, which can't host an inline overlay on a native widget), so both animate
 * identically.
 */
public class Spinner {
    /** Degrees the spinner advances per animation tick. */
    public static final int STEP_DEGREES = 24;

    /** How long to sleep between animation ticks, in milliseconds. */
    public static final int TICK_MS = 120;

    /**
     * Draws the spinner centered at (cx, cy) with the given diameter, at the given
     * animation frame. ringColor is the ring itself; holeColor is punched out of the
     * middle (fillArc has no "ring" primitive) and should match whatever's behind it.
     */
    public static void draw(Graphics g, int cx, int cy, int size, int frame, int ringColor, int holeColor) {
        int x = cx - size / 2;
        int y = cy - size / 2;
        int angle = (frame * STEP_DEGREES) % 360;

        g.setColor(ringColor);
        g.fillArc(x, y, size, size, angle, 300);

        int holeInset = size / 4;
        g.setColor(holeColor);
        g.fillArc(x + holeInset, y + holeInset, size - holeInset * 2, size - holeInset * 2, 0, 360);
    }
}
