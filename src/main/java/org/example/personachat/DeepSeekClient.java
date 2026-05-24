package org.example.personachat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 通用 OpenAI 兼容 chat/completions 客户端。市面上大多数厂家（DeepSeek/OpenAI/Moonshot/Zhipu/Qwen/Gemini/OpenRouter/SiliconFlow…）都提供此协议。 */
public class DeepSeekClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private static final String DEFAULT_URL = "https://api.deepseek.com/chat/completions";

    public static String chat(String apiUrl, String apiKey, String model, JSONArray messages) throws Exception {
        String url = (apiUrl == null || apiUrl.isBlank()) ? DEFAULT_URL : apiUrl.trim();
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 1.0)
                .put("max_tokens", 400)
                .put("stream", false);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        Log.w("API", "POST " + url + " model=" + model + " msgs=" + messages.length());
        HttpResponse<String> resp = HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            Log.w("API", "HTTP " + resp.statusCode() + " body=" + Log.cut(resp.body(), 300));
            throw new RuntimeException("API " + resp.statusCode() + "：" + resp.body());
        }
        JSONObject choice = new JSONObject(resp.body()).getJSONArray("choices").getJSONObject(0);
        String content = choice.getJSONObject("message").optString("content", "").trim();
        if (content.isEmpty()) {
            Log.w("API", "空content finish=" + choice.optString("finish_reason", "?") + " body=" + Log.cut(resp.body(), 300));
        }
        return content;
    }
}
