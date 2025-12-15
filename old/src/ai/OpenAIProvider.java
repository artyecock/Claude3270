package ai;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class OpenAIProvider implements AIModelProvider {

    private final String apiKey;
    private final String[] models;

    public OpenAIProvider(String apiKey, String[] models) {
        this.apiKey = apiKey;
        this.models = models;
    }

    @Override
    public String[] listModels() {
        return models;
    }

    @Override
    public String send(String model, String prompt, String context) throws Exception {
        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        conn.addRequestProperty("Content-Type", "application/json");
        conn.addRequestProperty("Authorization", "Bearer " + apiKey);

        String payload =
            "{ \"model\": \"" + model + "\", " +
            "\"messages\": [" +
                "{\"role\":\"system\",\"content\":\"You are assisting a TN3270 mainframe user.\"}," +
                "{\"role\":\"user\",\"content\": " + quote(context + "\n\n" + prompt) + "}" +
            "] }";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        InputStream in = conn.getResponseCode() < 300
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }

        String json = sb.toString();
        return extractContent(json);
    }

    // crude JSON extraction for "content"
    private String extractContent(String json) {
        int i = json.indexOf("\"content\"");
        if (i < 0) return json;
        int start = json.indexOf(':', i) + 1;
        int q1 = json.indexOf('"', start);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return json;
        return json.substring(q1 + 1, q2);
    }

    private String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}

