package com.tn3270.ai;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
//import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection; 

public class OpenAIProvider implements AIModelProvider {

    private final String apiKey;
    private final String[] models;
    private final String endpoint;

    public OpenAIProvider(String apiKey, String[] models) {
        this(apiKey, models, "https://api.openai.com/v1/chat/completions");
    }

    public OpenAIProvider(String apiKey, String[] models, String endpoint) {
        this.apiKey = apiKey;
        this.models = models == null ? new String[0] : models;
        this.endpoint = endpoint != null ? endpoint : "https://api.openai.com/v1/chat/completions";
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
        String fullContext = (context == null ? "" : context + "\n\n");
        String fullContent = fullContext + (prompt == null ? "" : prompt);

        byte[] payloadBytes = buildPayloadBytes(model, fullContent, stream);

        URL url = new URI(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(stream ? 0 : 60000);

        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payloadBytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream in = (code < 300) ? conn.getInputStream() : conn.getErrorStream();

        if (code >= 300) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            throw new IOException("API Error " + code + ": " + sb.toString());
        }

        if (stream && onChunk != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        String chunk = extractJsonField(data, "content");
                        if (!chunk.isEmpty()) {
                            onChunk.accept(chunk);
                        }
                    }
                }
            }
            return "";
        } else {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return extractJsonField(sb.toString(), "content");
        }
    }

    private byte[] buildPayloadBytes(String model, String content, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":").append(jsonEscape(model)).append(",");
        sb.append("\"stream\":").append(stream).append(",");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"user\",\"content\":").append(jsonEscape(content)).append("}");
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String extractJsonField(String json, String key) {
        String search = "\"" + key + "\"";
        int startIdx = json.lastIndexOf(search);
        if (startIdx < 0) return "";

        int colonIdx = json.indexOf(':', startIdx + search.length());
        if (colonIdx < 0) return "";

        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart < 0) return "";

        int valueEnd = valueStart + 1;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == '\\') {
                valueEnd += 2;
                continue;
            }
            if (c == '"') break;
            valueEnd++;
        }

        if (valueEnd >= json.length()) return "";
        String content = json.substring(valueStart + 1, valueEnd);
        return content.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
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
                    if (c < 0x20 || c > 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
