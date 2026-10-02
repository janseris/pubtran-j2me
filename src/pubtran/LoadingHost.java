package pubtran;


/**
 * Implemented by a JP screen that can show its own inline loading animation - see
 * StartScreen - instead of having RequestThread switch away to the separate
 * LoadingScreen while a request is in flight.
 *
 * Only a plain Canvas can do this (it owns its own paint()); the List/Form-based JP
 * screens can't draw over a native widget, so they don't implement this and RequestThread
 * falls back to LoadingScreen for them.
 */
public interface LoadingHost {
    void setLoading(boolean loading);
}
