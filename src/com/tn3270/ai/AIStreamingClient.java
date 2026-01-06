package com.tn3270.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class AIStreamingClient {

	private final AIModelProvider provider;
	private final ExecutorService executor;
	private final AtomicBoolean isStreaming = new AtomicBoolean(false);

	public AIStreamingClient(AIModelProvider provider) {
		this.provider = provider;
		this.executor = Executors.newSingleThreadExecutor();
	}

	public void sendStream(String model, String prompt, String context, Consumer<String> onChunk,
			Consumer<Exception> onError, Runnable onComplete) {

		// 1. Check busy state
		if (isStreaming.getAndSet(true)) {
			onError.accept(new IllegalStateException("AI is currently generating a response. Please wait."));
			return;
		}

		// 2. Offload to background thread
		executor.submit(() -> {
			try {
				provider.sendStream(model, prompt, context, onChunk,
						// On Error (Wrapper)
						(ex) -> {
							isStreaming.set(false); // CRITICAL: Reset flag on error
							onError.accept(ex);
						},
						// On Complete (Wrapper)
						() -> {
							isStreaming.set(false); // CRITICAL: Reset flag on success
							onComplete.run();
						});
			} catch (Exception e) {
				// Handle immediate failures (e.g. config errors)
				isStreaming.set(false);
				onError.accept(e);
			}
		});
	}

	public boolean isBusy() {
		return isStreaming.get();
	}

	public void shutdown() {
		executor.shutdownNow();
	}
}
