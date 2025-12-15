package com.tn3270.ai;

import java.util.function.Consumer;

public interface AIModelProvider {
    String[] listModels();

    String send(String model, String prompt, String context) throws Exception;

    // default streaming fallback: call send() and deliver as single chunk
    default void sendStream(String model, String prompt, String context, Consumer<String> onChunk,
            Consumer<Exception> onError, Runnable onComplete) throws Exception {
        try {
            String r = send(model, prompt, context);
            onChunk.accept(r);
            onComplete.run();
        } catch (Exception ex) {
            onError.accept(ex);
            onComplete.run();
        }
    }
}
