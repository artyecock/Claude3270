package com.tn3270.ai;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class OllamaProvider implements AIModelProvider {

    private final String endpoint;
    private final String[] models;

    public OllamaProvider(String endpoint, String[] models) {
        this.endpoint = (endpoint != null && !endpoint.isEmpty()) 
            ? endpoint.replaceAll("/+$", "") 
            : "http://localhost:11434";
        this.models = (models == null) ? new String[0] : models;
    }

    @Override
    public String[] listModels() {
        return models;
    }

    @Override
    public String send(String model, String prompt, String context) throws Exception {
        return doRequest(model, prompt, context, false, null);
    }

    @Override
    public void sendStream(String model, String prompt, String context, Consumer<String> onChunk,
                           Consumer<Exception> onError, Runnable onComplete) throws Exception {
        try {
            doRequest(model, prompt, context, true, onChunk);
            onComplete.run();
        } catch (Exception e) {
            onError.accept(e);
            onComplete.run();
        }
    }

    private String doRequest(String model, String prompt, String context, boolean stream, Consumer<String> onChunk)
            throws Exception {
        
        String fullPrompt = (context != null && !context.isEmpty()) ? context + "\n\n" + prompt : prompt;

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"model\": \"").append(jsonEscape(model)).append("\",");
        json.append("\"stream\": ").append(stream).append(",");
        json.append("\"messages\": [");
        json.append("{ \"role\": \"user\", \"content\": \"").append(jsonEscape(fullPrompt)).append("\" }");
        json.append("]");
        json.append("}");

        URL url = new URI(endpoint + "/api/chat").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(stream ? 0 : 120000);
        conn.setRequestProperty("Content-Type", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream in = (code < 300) ? conn.getInputStream() : conn.getErrorStream();

        if (code >= 300) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder err = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) err.append(line);
                throw new IOException("Ollama Error " + code + ": " + err.toString());
            }
        }

        if (stream && onChunk != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String content = extractOllamaContent(line);
                    if (content != null && !content.isEmpty()) {
                        onChunk.accept(content);
                    }
                }
            }
            return "";
        } else {
            StringBuilder fullResponse = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String content = extractOllamaContent(line);
                    if (content != null) fullResponse.append(content);
                }
            }
            return fullResponse.toString();
        }
    }

    private String extractOllamaContent(String json) {
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else {
                if (c == '\\') escaped = true;
                else if (c == '"') break;
                else sb.append(c);
            }
        }
        return sb.toString();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
