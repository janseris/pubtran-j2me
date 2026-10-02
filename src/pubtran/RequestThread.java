package pubtran;

import com.gtrxac.discord.App;

import javax.microedition.lcdui.*;

/**
 * Runs one pubtran-backend API call (see PubtranApi, RequestCallback) on a background thread, so
 * the request never blocks the LCDUI event thread.
 *
 * If errorReturnScreen implements LoadingHost (currently just StartScreen), it's
 * asked to show its own inline loading overlay instead of navigating away - the screen
 * stays exactly where it is and only its own paint() changes. Otherwise (the List/
 * Form-based JP screens, which can't host an overlay on top of a native widget), this
 * falls back to switching to LoadingScreen, a small full-screen loading animation.
 *
 * Either way, a dismissable error alert (then back to errorReturnScreen) is shown if
 * the request fails.
 */
public class RequestThread extends Thread {
    private RequestCallback callback;
    private Displayable errorReturnScreen;
    private LoadingHost host;

    /** The request whose loading UI is shown, so "Zrušit" can release it. */
    private static volatile RequestThread active;
    /** Set by cancelActive(): the UI has moved on, ignore whatever this thread returns. */
    private volatile boolean abandoned;

    /**
     * "Zrušit": aborts the request in flight and gives the screen back at once, even if
     * the network call can't be interrupted (e.g. blocked in Connector.open on the 9300);
     * its late result or error is then ignored.
     */
    /** Stall watchdog: if thread t is the active request, give the screen back with an error. */
    public static void stall(Thread t, String reason) {
        RequestThread r = active;
        if (r == null || r != t) return;
        r.abandoned = true;
        active = null;
        if (r.host != null) r.host.setLoading(false);
        Alert alert = new Alert("Chyba požadavku", reason, null, AlertType.ERROR);
        alert.setTimeout(Alert.FOREVER);
        App.disp.disp.setCurrent(alert, r.errorReturnScreen);
    }

    public static void cancelActive() {
        RequestThread t = active;
        if (t == null) return;
        t.abandoned = true;
        active = null;
        PubtranApi.cancel("Zrušeno", true);
        if (t.host != null) t.host.setLoading(false);
        App.disp.setCurrent(t.errorReturnScreen);
    }

    public RequestThread(RequestCallback callback, Displayable errorReturnScreen) {
        this.callback = callback;
        this.errorReturnScreen = errorReturnScreen;
    }

    public void run() {
        host = (errorReturnScreen instanceof LoadingHost) ? (LoadingHost) errorReturnScreen : null;
        active = this;

        if (host != null) {
            host.setLoading(true);
        }
        else {
            LoadingScreen ls = new LoadingScreen();
            final Command cancel = new Command("Zrušit", Command.STOP, 0);
            ls.addCommand(cancel);
            ls.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    if (c == cancel) cancelActive();
                }
            });
            App.disp.setCurrent(ls);
        }

        try {
            Object result = callback.request();
            if (abandoned) return;
            active = null;
            if (host != null) host.setLoading(false);
            callback.onSuccess(result);
        }
        catch (Exception e) {
            if (abandoned) return;
            active = null;
            if (host != null) host.setLoading(false);
            e.printStackTrace();
            if (PubtranApi.cancelledByUser) { // "Zrušit": back without an error
                App.disp.setCurrent(errorReturnScreen);
                return;
            }
            Alert alert = new Alert("Chyba požadavku", e.toString(), null, AlertType.ERROR);
            alert.setTimeout(Alert.FOREVER);
            App.disp.disp.setCurrent(alert, errorReturnScreen);
        }
    }
}
