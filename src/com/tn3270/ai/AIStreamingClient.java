package com.tn3270.ai;

import java.util.function.Consumer;

public class AIStreamingClient {
    private final AIModelProvider provider;

    public AIStreamingClient(AIModelProvider provider) {
        this.provider = provider;
    }

    public void sendStream(String model, String prompt, String context, Consumer<String> onChunk,
            Consumer<Exception> onError, Runnable onComplete) {
        new Thread(() -> {
            try {
                provider.sendStream(model, prompt, context, onChunk, onError, onComplete);
            } catch (Exception ex) {
                try {
                    // Fallback to non-streaming
                    String r = provider.send(model, prompt, context);
                    onChunk.accept(r);
                    onComplete.run();
                } catch (Exception ex2) {
                    onError.accept(ex2);
                    onComplete.run();
                }
            }
        }, "AIStream-" + model).start();
    }
}
