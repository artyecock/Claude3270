package ai;

import java.io.*;
import java.util.*;

public class AIConfig {

    private final Properties props = new Properties();

    public AIConfig(String path) {
        load(path);
    }

    private void load(String path) {
        File f = new File(path);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            props.load(in);
        } catch (Exception e) {
            System.err.println("AIConfig: error reading " + path + ": " + e);
        }
    }

    public boolean isEnabled() {
        return "true".equalsIgnoreCase(props.getProperty("ai.enabled", "false"));
    }

    public String getProvider() {
        return props.getProperty("ai.provider", "openai");
    }

    public String getApiKey() {
        return props.getProperty("ai.apiKey", "");
    }

    public String[] getModels() {
        String s = props.getProperty("ai.models", "gpt-4o-mini");
        return s.split(",");
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }
}

