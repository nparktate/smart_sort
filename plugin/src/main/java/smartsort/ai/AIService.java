package smartsort.ai;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;
import smartsort.SmartSortPlugin;
import smartsort.util.DebugLogger;
import smartsort.util.RateLimiter;

public class AIService {

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // Increased timeout
        .build();
    private final String apiKey;
    private final DebugLogger debug;
    private final SmartSortPlugin plugin;
    private final RateLimiter rateLimiter;

    public AIService(SmartSortPlugin plugin, DebugLogger debug) {
        this.plugin = plugin;
        this.debug = debug;
        this.apiKey = plugin.getConfig().getString("openai.api_key", "");
        int maxRequests = plugin
            .getConfig()
            .getInt("performance.max_requests", 3); // Reduced from 5 to 3
        int perSeconds = plugin
            .getConfig()
            .getInt("performance.per_seconds", 2); // Increased from 1 to 2
        this.rateLimiter = new RateLimiter(maxRequests, perSeconds, debug);

        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            plugin
                .getLogger()
                .severe(
                    "OpenAI key missing or invalid – plugin stays enabled but AI features are OFF"
                );
        }
    }

    public void shutdown() {
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }

    public String selectModel(int itemCount) {
        if (plugin.getConfig().getBoolean("openai.dynamic_model", false)) {
            int smallThreshold = plugin
                .getConfig()
                .getInt("openai.model_thresholds.small", 12);
            int mediumThreshold = plugin
                .getConfig()
                .getInt("openai.model_thresholds.medium", 27);

            if (itemCount <= smallThreshold) {
                return plugin
                    .getConfig()
                    .getString("openai.models.small", "gpt-3.5-turbo");
            } else if (itemCount <= mediumThreshold) {
                return plugin
                    .getConfig()
                    .getString("openai.models.medium", "gpt-4o");
            } else {
                return plugin
                    .getConfig()
                    .getString("openai.models.large", "gpt-4-turbo-preview");
            }
        }
        return plugin.getConfig().getString("openai.model", "gpt-4o");
    }

    public CompletableFuture<String> chat(String prompt) {
        return chat(prompt, 0);
    }

    public CompletableFuture<String> chat(String prompt, int itemCount) {
        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            debug.console(
                "[AI] ERROR: API key is missing or using default value. Please set a valid key in config.yml"
            );
            return CompletableFuture.completedFuture("");
        }

        if (!rateLimiter.tryAcquire()) {
            debug.console(
                "[AI] Rate limit reached, request queued for later execution"
            );
            CompletableFuture<String> future = new CompletableFuture<>();
            plugin
                .getServer()
                .getScheduler()
                .runTaskLater(
                    plugin,
                    () -> chat(prompt, itemCount).thenAccept(future::complete),
                    40L // Increased from 20L to 40L (2 seconds)
                );
            return future;
        }

        String selectedModel = selectModel(itemCount);
        debug.console(
            "[AI] Using model: " +
            selectedModel +
            " for " +
            itemCount +
            " items"
        );

        JSONObject json = new JSONObject()
            .put("model", selectedModel)
            .put("temperature", 0.3)
            .put(
                "messages",
                List.of(
                    new JSONObject().put("role", "user").put("content", prompt)
                )
            );

        RequestBody body = RequestBody.create(
            json.toString(),
            MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();

        CompletableFuture<String> future = new CompletableFuture<>();
        http
            .newCall(request)
            .enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException e) {
                        debug.console("[AI] Call failed: " + e.getMessage());
                        future.complete("");
                    }

                    public void onResponse(Call call, Response rsp)
                        throws IOException {
                        try {
                            String responseBody = rsp.body().string();
                            debug.console("[AI] Response code: " + rsp.code());

                            if (rsp.code() != 200) {
                                debug.console(
                                    "[AI] Error response: " + responseBody
                                );
                                future.complete("");
                                return;
                            }

                            JSONObject json = new JSONObject(responseBody);
                            if (
                                !json.has("choices") ||
                                json.getJSONArray("choices").length() == 0
                            ) {
                                debug.console(
                                    "[AI] Invalid response format: " +
                                    responseBody
                                );
                                future.complete("");
                                return;
                            }

                            String content = json
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                            future.complete(content);
                        } catch (Exception ex) {
                            debug.console(
                                "[AI] Response parsing error: " +
                                ex.getMessage()
                            );
                            future.complete("");
                        }
                    }
                }
            );
        return future;
    }

    public CompletableFuture<String> chat(String prompt, String forcedModel) {
        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            debug.console(
                "[AI] ERROR: API key is missing or using default value. Please set a valid key in config.yml"
            );
            return CompletableFuture.completedFuture("");
        }

        if (!rateLimiter.tryAcquire()) {
            debug.console(
                "[AI] Rate limit reached, request queued for later execution"
            );
            CompletableFuture<String> future = new CompletableFuture<>();
            plugin
                .getServer()
                .getScheduler()
                .runTaskLater(
                    plugin,
                    () ->
                        chat(prompt, forcedModel).thenAccept(future::complete),
                    40L // Increased from 20L to 40L (2 seconds)
                );
            return future;
        }

        debug.console("[AI] Using forced model: " + forcedModel);

        JSONObject json = new JSONObject()
            .put("model", forcedModel)
            .put("temperature", 0.3)
            .put(
                "messages",
                List.of(
                    new JSONObject().put("role", "user").put("content", prompt)
                )
            );

        RequestBody body = RequestBody.create(
            json.toString(),
            MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();

        CompletableFuture<String> future = new CompletableFuture<>();
        http
            .newCall(request)
            .enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException e) {
                        debug.console("[AI] Call failed: " + e.getMessage());
                        future.complete("");
                    }

                    public void onResponse(Call call, Response rsp)
                        throws IOException {
                        try {
                            String responseBody = rsp.body().string();
                            debug.console("[AI] Response code: " + rsp.code());

                            if (rsp.code() != 200) {
                                debug.console(
                                    "[AI] Error response: " + responseBody
                                );
                                future.complete("");
                                return;
                            }

                            JSONObject json = new JSONObject(responseBody);
                            if (
                                !json.has("choices") ||
                                json.getJSONArray("choices").length() == 0
                            ) {
                                debug.console(
                                    "[AI] Invalid response format: " +
                                    responseBody
                                );
                                future.complete("");
                                return;
                            }

                            String content = json
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                            future.complete(content);
                        } catch (Exception ex) {
                            debug.console(
                                "[AI] Response parsing error: " +
                                ex.getMessage()
                            );
                            future.complete("");
                        }
                    }
                }
            );
        return future;
    }
}
