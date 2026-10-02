package pubtran;


/**
 * A single pubtran-backend request plus what to do with its result, run by RequestThread
 * on a background thread so it never blocks the LCDUI event thread.
 */
public interface RequestCallback {
    /** Runs on a background thread - do the actual network call + JSON parsing here. */
    Object request() throws Exception;

    /** Runs on the same background thread, only if request() succeeded. */
    void onSuccess(Object result);
}
