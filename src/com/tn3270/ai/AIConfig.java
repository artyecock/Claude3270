package com.tn3270.ai;

import java.io.*;
import java.util.Arrays;
import java.util.Properties;

public class AIConfig {
    private final Properties props = new Properties();
    private final File file;

    public AIConfig(String path) {
        this.file = new File(path);
        load();
    }

    public void load() {
        try {
            props.clear();
            // DEBUG: Print where we are looking
            System.out.println("Loading AI Config from: " + file.getAbsolutePath());
            
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                    System.out.println("Config loaded successfully. Keys found: " + props.keySet().size());
                }
            } else {
                System.out.println("!!! Config file NOT found at that path. Using defaults.");
                createDefaults();
            }
        } catch (Exception e) {
            System.err.println("AIConfig.load: " + e.getMessage());
        }
    }

    public void save() {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "TN3270 AI Configuration");
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // --- Accessors ---

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
    }

    // --- Legacy Helpers (kept for compatibility) ---
    
    public String[] getModels() {
        String m = props.getProperty("ai.models");
        if (m == null || m.trim().isEmpty()) {
            return new String[]{"gpt-4o", "gpt-4o-mini"};
        }
        return Arrays.stream(m.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    public String getApiKey() { return props.getProperty("ai.apiKey", ""); }
    public String getEndpoint() { return props.getProperty("ai.endpoint", "http://localhost:11434"); }
    
    public File file() { return file; }

    // Optional: Generate default file if missing so user sees the structure
    private void createDefaults() {
        set("ai.provider", "openai");
        set("ai.apiKey", "");
        set("ai.models", "gpt-4o, gpt-4o-mini");
        
        // Add sample context to help user get started
        set("context.tso_rexx.prompt", "You are an expert in z/OS TSO/E REXX.");
        set("context.tso_rexx.ref", "z/OS TSO/E REXX Reference");
        
        save();
    }
}