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
                    System.out.println("Config loaded successfully. Keys found: " + props.keySet());
                }
            } else {
                System.out.println("!!! Config file NOT found at that path. Using defaults.");
            }
        } catch (Exception e) {
            System.err.println("AIConfig.load: " + e.getMessage());
        }
    }

    public void loadOLD() {
        try {
            props.clear();
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                }
            }
        } catch (Exception e) {
            System.err.println("AIConfig.load: " + e.getMessage());
        }
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    // --- Legacy Helpers ---
    public String[] getModels() {
        String m = props.getProperty("ai.models");
        if (m == null) return new String[]{"gpt-4o", "gpt-4o-mini"};
        return Arrays.stream(m.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    public String getApiKey() { return props.getProperty("ai.apiKey", ""); }
    public String getEndpoint() { return props.getProperty("ai.endpoint", "http://localhost:11434"); }
    
    public File file() { return file; }
}
