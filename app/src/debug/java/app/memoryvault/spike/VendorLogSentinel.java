package app.memoryvault.spike;

import android.os.Handler;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.listener.ResultListener;
import ai.pleos.playground.stt.service.SttCallback;
import ai.pleos.playground.stt.viewmodel.SttViewModel;
import java.util.function.Consumer;

/** Synthetic callback privacy check only. This is never evidence of speech recognition. */
final class VendorLogSentinel {
    static final String SENTINEL = "MV02_SYNTHETIC_PRIVACY_SENTINEL_481723";

    static void run(Handler handler, Consumer<String> event) {
        SttViewModel model = new SttViewModel();
        ResultListener listener = new ResultListener() {
            @Override public void onUpdated(String value, boolean completed) {
                event.accept("SDK_SENTINEL_CALLBACK completed=" + completed + " match=" + SENTINEL.equals(value));
            }
            @Override public void onUpdatedEpdData(long on, long off) { }
            @Override public void onStartedRecognition() { }
            @Override public void onEndedRecognition() { }
            @Override public void onError(ErrorType error) { event.accept("SDK_SENTINEL_ERROR=" + error.name()); }
            @Override public void onReady() { }
            @Override public void onUpdatedRms(double rms) { }
        };
        // The original 3.3.1 AAR exposes obfuscated add/remove methods a/b.
        model.a(listener);
        new SttCallback(model).onResponse("UpdateSttResult", SENTINEL);
        handler.postDelayed(() -> {
            model.b(listener);
            event.accept("SDK_SENTINEL_DETACHED");
        }, 1_000);
        event.accept("SDK_SENTINEL_DISPATCHED synthetic=true");
    }
}
