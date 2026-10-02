package pubtran;


import javax.microedition.lcdui.*;

/**
 * Full-screen loading animation shown by RequestThread while a pubtran-backend request is
 * in flight, for screens that can't host their own inline loading overlay (the List/
 * Form-based JP screens - a native List or Form can't have custom graphics drawn over
 * it). StartScreen is a plain Canvas and instead shows the same spinner (see
 * Spinner) as an overlay on itself, staying put rather than switching to this screen
 * - see RequestThread and LoadingHost.
 *
 * Deliberately independent from the Discord app's own LoadingScreen/Theme/font
 * system, since these screens are meant to be simple and self-contained.
 */
public class LoadingScreen extends Canvas {
    private static final int BG_COLOR = 0x2B2D31;
    private static final int SPINNER_COLOR = 0x5865F2;
    private static final int TEXT_COLOR = 0xB5BAC1;

    /** Supplies the status lines shown under the spinner (e.g. the HTTPS test's progress). */
    public interface StatusSource {
        String[] statusLines();
    }

    private boolean running = true;
    private int frame = 0;
    private StatusSource source;

    public LoadingScreen() {
        setTitle("Loading...");
        startTicker();
    }

    /** Loading screen with its own title and status lines instead of PubtranApi's progress. */
    public LoadingScreen(String title, StatusSource source) {
        setTitle(title);
        this.source = source;
        startTicker();
    }

    protected void showNotify() {
        if (!running) {
            running = true;
            startTicker();
        }
    }

    private void startTicker() {
        Thread ticker = new Thread() {
            public void run() {
                while (running) {
                    frame++;
                    repaint();
                    try {
                        Thread.sleep(Spinner.TICK_MS);
                    }
                    catch (InterruptedException e) {}
                }
            }
        };
        ticker.start();
    }

    /** Stops the animation thread once this screen is no longer the one shown. */
    protected void hideNotify() {
        running = false;
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, w, h);

        if (source != null) {
            String[] lines = null;
            try { lines = source.statusLines(); } catch (Throwable e) {}
            if (lines == null) lines = new String[0];
            Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            int lh = f.getHeight();
            int size = h - 12 - lines.length * lh;
            if (size > Math.min(w, h) / 3) size = Math.min(w, h) / 3;
            if (size < 20) size = 20;
            int top = (h - size - 6 - lines.length * lh) / 2;
            if (top < 2) top = 2;
            Spinner.draw(g, w / 2, top + size / 2, size, frame, SPINNER_COLOR, BG_COLOR);
            g.setFont(f);
            g.setColor(TEXT_COLOR);
            int y = top + size + 6;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i] != null) g.drawString(lines[i], w / 2, y, Graphics.TOP | Graphics.HCENTER);
                y += lh;
            }
            return;
        }

        int size = Math.min(w, h) / 3;
        if (size < 24) size = 24;
        Spinner.draw(g, w / 2, h / 2 - 6, size, frame, SPINNER_COLOR, BG_COLOR);

        // Live transfer status - "Connecting..." vs "X KB / Y KB" tells a request stuck
        // resolving/reaching the server apart from one that's just slow to download.
        g.setColor(TEXT_COLOR);
        g.drawString(PubtranApi.progressText(), w / 2, h / 2 + size / 2 + 4, Graphics.TOP | Graphics.HCENTER);
    }
}
