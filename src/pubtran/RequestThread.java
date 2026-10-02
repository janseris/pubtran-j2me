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

    public RequestThread(RequestCallback callback, Displayable errorReturnScreen) {
        this.callback = callback;
        this.errorReturnScreen = errorReturnScreen;
    }

    public void run() {
        LoadingHost host = (errorReturnScreen instanceof LoadingHost)
            ? (LoadingHost) errorReturnScreen : null;

        if (host != null) {
            host.setLoading(true);
        }
        else {
            LoadingScreen ls = new LoadingScreen();
            final Command cancel = new Command("Zrušit", Command.STOP, 0);
            ls.addCommand(cancel);
            ls.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    if (c == cancel) PubtranApi.cancel("Zrušeno", true);
                }
            });
            App.disp.setCurrent(ls);
        }

        try {
            Object result = callback.request();
            if (host != null) host.setLoading(false);
            callback.onSuccess(result);
        }
        catch (Exception e) {
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
