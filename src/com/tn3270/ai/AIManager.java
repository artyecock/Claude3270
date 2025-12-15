package com.tn3270.ai;

import java.awt.Frame;
import java.util.*;
import java.util.function.Consumer;

/**
 * Manages AI Providers and routes requests.
 * Supports Aliasing: "RealModelName (Display Name)" in config.
 */
public class AIManager implements AIModelProvider {

    private static AIManager instance;
    private AIConfig config;
    private AIChatWindow activeWindow;

    // --- The Tuple Class ---
    private static class ModelRoute {
        final AIModelProvider provider;
        final String realModelName;

        ModelRoute(AIModelProvider provider, String realModelName) {
            this.provider = provider;
            this.realModelName = realModelName;
        }
    }

    // Maps "Display Name" (UI) -> Route (Provider + API Model Name)
    private final Map<String, ModelRoute> modelRouter = new LinkedHashMap<>(); // Linked to preserve order
    private final List<String> availableModels = new ArrayList<>();

    private AIManager() {
        config = new AIConfig("ai.conf");
        reloadConfig();
    }

    public static synchronized AIManager getInstance() {
        if (instance == null) instance = new AIManager();
        return instance;
    }

    public void reloadConfig() {
        config.load();
        modelRouter.clear();
        availableModels.clear();
        buildProviders();
    }

    public void showChatDialog(Frame owner, String selectedText) {
        showChatDialog(owner, selectedText, false);
    }

    public void showChatDialog(Frame owner, String selectedText, boolean autoSend) {
        if (activeWindow != null && activeWindow.isVisible()) {
            activeWindow.toFront();
            activeWindow.showWithPrefill(selectedText, config.get("ai.prompt.default", ""), autoSend);
            return;
        }
        AIChatWindow w = new AIChatWindow(owner, this, config);
        this.activeWindow = w;
        w.showWithPrefill(selectedText, config.get("ai.prompt.default", ""), autoSend);
    }

    // =======================================================================
    // ROUTING LOGIC
    // =======================================================================

    @Override
    public String[] listModels() {
        return availableModels.toArray(new String[0]);
    }

    @Override
    public String send(String displayModel, String prompt, String context) throws Exception {
        ModelRoute route = modelRouter.get(displayModel);
        if (route == null) throw new IllegalArgumentException("No provider configured for: " + displayModel);
        
        // Pass the REAL model name to the provider
        return route.provider.send(route.realModelName, prompt, context);
    }

    @Override
    public void sendStream(String displayModel, String prompt, String context, Consumer<String> onChunk,
                           Consumer<Exception> onError, Runnable onComplete) throws Exception {
        ModelRoute route = modelRouter.get(displayModel);
        if (route == null) {
            onError.accept(new IllegalArgumentException("No provider configured for: " + displayModel));
            return;
        }
        
        // Pass the REAL model name to the provider
        route.provider.sendStream(route.realModelName, prompt, context, onChunk, onError, onComplete);
    }

    // =======================================================================
    // CONFIGURATION PARSER
    // =======================================================================

    private void buildProviders() {
        String providersList = config.get("ai.providers", null);

        if (providersList != null && !providersList.trim().isEmpty()) {
            String[] ids = providersList.split(",");
            for (String id : ids) {
                id = id.trim();
                if (!id.isEmpty()) loadProvider(id);
            }
        } else {
            loadLegacyProvider();
        }
        
        System.out.println("AI Manager loaded. Models: " + availableModels);
    }

    private void loadProvider(String id) {
        String type = config.get(id + ".type", "openai");
        String modelsStr = config.get(id + ".models", "");
        
        AIModelProvider provider;

        if ("ollama".equalsIgnoreCase(type)) {
            String endpoint = config.get(id + ".endpoint", "http://localhost:11434");
            provider = new OllamaProvider(endpoint, null);
        } else {
            String apiKey = config.get(id + ".apiKey", "");
            String endpoint = config.get(id + ".endpoint", "https://api.openai.com/v1/chat/completions");
            provider = new OpenAIProvider(apiKey, null, endpoint);
        }

        // PARSE MODELS AND ALIASES
        // Syntax: "RealName (Display Name)" or just "RealName"
        String[] entries = modelsStr.split(",");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;

            String displayName;
            String realName;

            int openParen = entry.indexOf('(');
            int closeParen = entry.lastIndexOf(')');

            if (openParen > 0 && closeParen > openParen) {
                // Found parens: extract names
                realName = entry.substring(0, openParen).trim();
                displayName = entry.substring(openParen + 1, closeParen).trim();
            } else {
                // No parens: simple case
                realName = entry;
                displayName = entry;
            }

            modelRouter.put(displayName, new ModelRoute(provider, realName));
            if (!availableModels.contains(displayName)) {
                availableModels.add(displayName);
            }
        }
    }

    private void loadLegacyProvider() {
        String providerName = config.get("ai.provider", "openai");
        String[] models = config.getModels(); 
        
        AIModelProvider provider;
        if ("ollama".equalsIgnoreCase(providerName)) {
            provider = new OllamaProvider(config.getEndpoint(), models);
        } else if ("google".equalsIgnoreCase(providerName)) {
            String ep = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            provider = new OpenAIProvider(config.getApiKey(), models, ep);
        } else {
            provider = new OpenAIProvider(config.getApiKey(), models);
        }

        for (String m : models) {
            modelRouter.put(m, new ModelRoute(provider, m));
            availableModels.add(m);
        }
    }
}
