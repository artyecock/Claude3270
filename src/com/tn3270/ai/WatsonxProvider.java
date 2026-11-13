package com.tn3270.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.tn3270.util.LoggerSetup;

/**
 * AIModelProvider implementation for IBM watsonx.ai.
 *
 * Configuration properties (in aiconfig.properties):
 *
 *   <id>.type        = watsonx
 *   <id>.apikey      = <IBM Cloud API key>
 *   <id>.project_id  = <watsonx.ai project GUID>
 *   <id>.region      = us-south   (optional, default: us-south)
 *   <id>.models      = ibm/granite-3-3-8b-instruct, meta-llama/llama-3-3-70b-instruct
 *
 * The IBM Cloud API key is exchanged for a short-lived IAM bearer token automatically.
 * Tokens are cached and refreshed ~1 minute before expiry.
 *
 * Supported model IDs (examples):
 *   ibm/granite-3-3-8b-instruct
 *   ibm/granite-3-3-2b-instruct
 *   meta-llama/llama-3-3-70b-instruct
 *   mistralai/mistral-large
 */
public class WatsonxProvider implements AIModelProvider {

    private static final Logger logger = LoggerSetup.getLogger(WatsonxProvider.class);

    private static final String IAM_TOKEN_URL = "https://iam.cloud.ibm.com/identity/token";
    private static final int    TOKEN_REFRESH_MARGIN_SEC = 60; // refresh 1 min before expiry

    private final String   apiKey;
    private final String   projectId;
    private final String   region;
    private final String[] models;

    // Cached IAM token state
    private String iamToken;
    private long   tokenExpiresAtMs = 0L;

    public WatsonxProvider(String apiKey, String projectId, String region, String[] models) {
        this.apiKey    = apiKey;
        this.projectId = projectId;
        this.region    = (region != null && !region.isBlank()) ? region.trim() : "us-south";
        this.models    = (models  != null) ? models : new String[0];
    }

    @Override
    public String[] listModels() {
        return models;
    }

    // -------------------------------------------------------------------------
    // Non-streaming send
    // -------------------------------------------------------------------------
    @Override
    public String send(String modelId, String prompt, String context) throws Exception {
        return doRequest(modelId, prompt, context, false, null);
    }

    // -------------------------------------------------------------------------
    // Streaming send  (Server-Sent Events)
    // -------------------------------------------------------------------------
    @Override
    public void sendStream(String modelId, String prompt, String context,
                           Consumer<String> onChunk,
                           Consumer<Exception> onError,
                           Runnable onComplete) throws Exception {
        try {
            doRequest(modelId, prompt, context, true, onChunk);
            onComplete.run();
        } catch (Exception e) {
            onError.accept(e);
            onComplete.run();
        }
    }

    // -------------------------------------------------------------------------
    // Core HTTP logic
    // -------------------------------------------------------------------------
    private String doRequest(String modelId, String prompt, String context,
                             boolean stream, Consumer<String> onChunk) throws Exception {

        String bearer = getBearerToken();

        // Build the watsonx.ai endpoint URL
        // Generation:        POST /ml/v1/text/generation?version=2023-05-29
        // Streaming:         POST /ml/v1/text/generation_stream?version=2023-05-29
        String path    = stream ? "/ml/v1/text/generation_stream" : "/ml/v1/text/generation";
        String urlStr  = "https://" + region + ".ml.cloud.ibm.com" + path + "?version=2023-05-29";

        String fullInput = buildInput(prompt, context);
        byte[] payload   = buildPayload(modelId, fullInput, stream);

        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(stream ? 0 : 120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept",       "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + bearer);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream in = (code < 300) ? conn.getInputStream() : conn.getErrorStream();

        if (code >= 300) {
            String body = readFully(in);
            throw new IOException("watsonx.ai API error " + code + ": " + body);
        }

        if (stream && onChunk != null) {
            // SSE stream: each event is a JSON object with a "results" array
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // watsonx stream lines are raw JSON (no "data: " prefix)
                    // Each object: {"model_id":"...","results":[{"generated_text":"...","stop_reason":...}],...}
                    String chunk = extractStreamChunk(line);
                    if (chunk != null && !chunk.isEmpty()) {
                        onChunk.accept(chunk);
                    }
                }
            }
            return "";
        } else {
            // Non-streaming: {"model_id":"...","results":[{"generated_text":"..."}],...}
            String body = readFully(in);
            return extractGeneratedText(body);
        }
    }

    // -------------------------------------------------------------------------
    // IAM token management
    // -------------------------------------------------------------------------
    private synchronized String getBearerToken() throws Exception {
        long nowMs = System.currentTimeMillis();
        if (iamToken != null && nowMs < tokenExpiresAtMs - (TOKEN_REFRESH_MARGIN_SEC * 1000L)) {
            return iamToken;
        }
        logger.info("WatsonxProvider: refreshing IAM token");

        String body = "grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey"
                    + "&apikey=" + urlEncode(apiKey);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        URL url = new URI(IAM_TOKEN_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream in = (code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readFully(in);

        if (code >= 300) {
            throw new IOException("IAM token request failed (" + code + "): " + response);
        }

        // Parse access_token and expiration from JSON
        String token   = extractJsonString(response, "access_token");
        String expiry  = extractJsonString(response, "expiration");  // Unix timestamp as string

        if (token == null || token.isEmpty()) {
            throw new IOException("IAM response did not contain access_token: " + response);
        }

        iamToken = token;
        if (expiry != null && !expiry.isEmpty()) {
            try {
                tokenExpiresAtMs = Long.parseLong(expiry) * 1000L;
            } catch (NumberFormatException ex) {
                // fallback: treat token as valid for 55 minutes
                tokenExpiresAtMs = nowMs + (55 * 60 * 1000L);
            }
        } else {
            tokenExpiresAtMs = nowMs + (55 * 60 * 1000L);
        }

        logger.info("WatsonxProvider: IAM token obtained, expires at " + tokenExpiresAtMs);
        return iamToken;
    }

    // -------------------------------------------------------------------------
    // Payload builder
    // -------------------------------------------------------------------------
    private byte[] buildPayload(String modelId, String input, boolean stream) {
        // watsonx.ai text generation request format
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model_id\":").append(jsonString(modelId)).append(",");
        sb.append("\"input\":").append(jsonString(input)).append(",");
        sb.append("\"project_id\":").append(jsonString(projectId)).append(",");
        sb.append("\"parameters\":{");
        sb.append("\"decoding_method\":\"greedy\",");
        sb.append("\"max_new_tokens\":2048,");
        sb.append("\"min_new_tokens\":1,");
        sb.append("\"repetition_penalty\":1.05");
        if (stream) {
            // streaming flag goes inside parameters for watsonx
            sb.append(",\"stream\":true");
        }
        sb.append("}");
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String buildInput(String prompt, String context) {
        if (context == null || context.isBlank()) {
            return prompt == null ? "" : prompt;
        }
        return context.trim() + "\n\n" + (prompt == null ? "" : prompt);
    }

    // -------------------------------------------------------------------------
    // Response parsers  (strstr-style, no external JSON library)
    // -------------------------------------------------------------------------

    /**
     * Extract generated_text from a non-streaming watsonx response body:
     * {"results":[{"generated_text":"...","stop_reason":"eos_token","generated_token_count":N}],...}
     */
    private String extractGeneratedText(String json) {
        // Find the first "generated_text" value in the results array
        return extractJsonString(json, "generated_text");
    }

    /**
     * Extract the generated chunk from a single SSE line (raw JSON object).
     */
    private String extractStreamChunk(String json) {
        return extractJsonString(json, "generated_text");
    }

    /**
     * Minimal JSON string-field extractor — finds the FIRST occurrence of "key":"value".
     * Handles basic escape sequences.  Consistent with the approach used elsewhere in the project.
     */
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;

        // Skip whitespace after colon
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) != '"') {
            char c = json.charAt(start);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                // Value is not a string (e.g. a number or boolean) — return raw token
                int end = start;
                while (end < json.length()) {
                    char e = json.charAt(end);
                    if (e == ',' || e == '}' || e == ']' || e == ' ') break;
                    end++;
                }
                return json.substring(start, end);
            }
            start++;
        }
        if (start >= json.length()) return null;

        // Parse quoted string
        int valueStart = start + 1;  // skip opening quote
        int pos = valueStart;
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '\\' && pos + 1 < json.length()) {
                char esc = json.charAt(pos + 1);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'u':
                        if (pos + 5 < json.length()) {
                            try {
                                int cp = Integer.parseInt(json.substring(pos + 2, pos + 6), 16);
                                sb.append((char) cp);
                                pos += 4;
                            } catch (NumberFormatException ex) {
                                sb.append(esc);
                            }
                        }
                        break;
                    default: sb.append(esc);
                }
                pos += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            pos++;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String readFully(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20 || c > 0x7e)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String urlEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int i = b & 0xFF;
            if ((i >= 'A' && i <= 'Z') || (i >= 'a' && i <= 'z')
                    || (i >= '0' && i <= '9')
                    || i == '-' || i == '_' || i == '.' || i == '~') {
                sb.append((char) i);
            } else {
                sb.append(String.format("%%%02X", i));
            }
        }
        return sb.toString();
    }
}