package smartsort.api.openai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.json.JSONObject;
import smartsort.SmartSortPlugin;
import smartsort.util.AsyncTaskManager;
import smartsort.util.DebugLogger;
import smartsort.util.RateLimiter;

public class OpenAIService {

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // Increased timeout
        .build();
    private final String apiKey;
    private final DebugLogger debug;
    private final SmartSortPlugin plugin;
    private final RateLimiter rateLimiter;
    private final Deque<PendingRequest> requestQueue;
    private final int queueMaxSize;
    private final AsyncTaskManager asyncTaskManager;

    public OpenAIService(SmartSortPlugin plugin, DebugLogger debug) {
        this.plugin = plugin;
        this.debug = debug;
        this.apiKey = loadApiKey();
        int maxRequests = plugin
            .getConfig()
            .getInt("performance.max_requests", 3); // Reduced from 5 to 3
        int perSeconds = plugin
            .getConfig()
            .getInt("performance.per_seconds", 2); // Increased from 1 to 2
        this.rateLimiter = new RateLimiter(maxRequests, perSeconds, debug);
        this.queueMaxSize = plugin
            .getConfig()
            .getInt("performance.queue_size", 20);
        this.requestQueue = new LinkedBlockingDeque<>(queueMaxSize);
        this.asyncTaskManager = plugin.getService(AsyncTaskManager.class);

        // Start queue processor
        startQueueProcessor();

        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            plugin
                .getLogger()
                .severe(
                    "OpenAI key missing or invalid – plugin stays enabled but AI features are OFF"
                );
        }
    }

    private String loadApiKey() {
        // Try environment variable first
        String key = System.getenv("OPENAI_API_KEY");
        if (key != null && !key.isEmpty()) return key;

        // Try separate file
        File keyFile = new File(plugin.getDataFolder(), "apikey.txt");
        if (keyFile.exists()) {
            try {
                return Files.readString(keyFile.toPath()).trim();
            } catch (IOException e) {
                plugin
                    .getLogger()
                    .severe("Failed to read API key: " + e.getMessage());
            }
        }

        // Fall back to config
        return plugin.getConfig().getString("openai.api_key", "");
    }

    public void shutdown() {
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }

    private void startQueueProcessor() {
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::processQueue, 20L, 20L);
    }

    private void processQueue() {
        if (requestQueue.isEmpty() || !rateLimiter.tryAcquire()) {
            return;
        }

        PendingRequest request = requestQueue.poll();
        if (request != null) {
            // Use AsyncTaskManager to execute the request
            asyncTaskManager.submitTask(() -> executeRequest(request));
        }
    }

    private void executeRequest(PendingRequest request) {
        if (request.forcedModel != null) {
            executeModelRequest(
                request.prompt,
                request.future,
                request.forcedModel
            );
        } else {
            executeCountRequest(
                request.prompt,
                request.future,
                request.itemCount
            );
        }
    }

    public String selectModel(int itemCount) {
        if (!plugin.getConfig().getBoolean("openai.dynamic_model", false)) {
            return plugin.getConfig().getString("openai.model", "gpt-4o");
        }

        // Simplified binary choice between models
        int smallThreshold = plugin
            .getConfig()
            .getInt("openai.model_thresholds.small", 13);

        if (itemCount <= smallThreshold) {
            return plugin
                .getConfig()
                .getString("openai.models.small", "gpt-3.5-turbo");
        } else {
            return plugin
                .getConfig()
                .getString("openai.models.large", "gpt-4o");
        }
    }

    public CompletableFuture<String> chat(String prompt) {
        return chat(prompt, 0);
    }

    public CompletableFuture<String> chat(String prompt, int itemCount) {
        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    debug.console(
                        "[AI] ERROR: API key is missing or using default value. Please set a valid key in config.yml"
                    );
                });
            return CompletableFuture.completedFuture("");
        }

        CompletableFuture<String> future = new CompletableFuture<>();

        if (!rateLimiter.tryAcquire()) {
            Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    debug.console(
                        "[AI] Rate limit reached, request queued for later execution"
                    );
                });

            // Instead of recursive scheduling, add to bounded queue
            PendingRequest request = new PendingRequest(
                prompt,
                future,
                itemCount
            );
            if (!requestQueue.offer(request)) {
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        debug.console("[AI] Queue full, request dropped");
                        future.complete("");
                    });
            }
            return future;
        }

        executeCountRequest(prompt, future, itemCount);
        return future;
    }

    private void executeCountRequest(
        String prompt,
        CompletableFuture<String> future,
        int itemCount
    ) {
        String selectedModel = selectModel(itemCount);
        Bukkit.getScheduler()
            .runTask(plugin, () -> {
                debug.console(
                    "[AI] Using model: " +
                    selectedModel +
                    " for " +
                    itemCount +
                    " items"
                );
            });

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

        http
            .newCall(request)
            .enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException e) {
                        Bukkit.getScheduler()
                            .runTask(plugin, () -> {
                                debug.console(
                                    "[AI] Call failed: " + e.getMessage()
                                );
                                future.complete("");
                            });
                    }

                    public void onResponse(Call call, Response rsp)
                        throws IOException {
                        try {
                            String responseBody = rsp.body().string();
                            final int responseCode = rsp.code();

                            Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    debug.console(
                                        "[AI] Response code: " + responseCode
                                    );

                                    if (responseCode != 200) {
                                        debug.console(
                                            "[AI] Error response: " + responseBody
                                        );
                                        future.complete("");
                                        return;
                                    }

                                    try {
                                        JSONObject json = new JSONObject(
                                            responseBody
                                        );
                                        if (
                                            !json.has("choices") ||
                                            json.getJSONArray("choices").length() ==
                                            0
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
                                });
                        } catch (Exception ex) {
                            Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    debug.console(
                                        "[AI] Response reading error: " +
                                        ex.getMessage()
                                    );
                                    future.complete("");
                                });
                        }
                    }
                }
            );
    }

    public CompletableFuture<String> chat(String prompt, String forcedModel) {
        if (apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    debug.console(
                        "[AI] ERROR: API key is missing or using default value. Please set a valid key in config.yml"
                    );
                });
            return CompletableFuture.completedFuture("");
        }

        CompletableFuture<String> future = new CompletableFuture<>();

        if (!rateLimiter.tryAcquire()) {
            Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    debug.console(
                        "[AI] Rate limit reached, request queued for later execution"
                    );
                });

            // Add to bounded queue instead of recursive scheduling
            PendingRequest request = new PendingRequest(
                prompt,
                future,
                forcedModel
            );
            if (!requestQueue.offer(request)) {
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        debug.console("[AI] Queue full, request dropped");
                        future.complete("");
                    });
            }
            return future;
        }

        executeModelRequest(prompt, future, forcedModel);
        return future;
    }

    private void executeModelRequest(
        String prompt,
        CompletableFuture<String> future,
        String forcedModel
    ) {
        Bukkit.getScheduler()
            .runTask(plugin, () -> {
                debug.console("[AI] Using forced model: " + forcedModel);
            });

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

        http
            .newCall(request)
            .enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException e) {
                        Bukkit.getScheduler()
                            .runTask(plugin, () -> {
                                debug.console(
                                    "[AI] Call failed: " + e.getMessage()
                                );
                                future.complete("");
                            });
                    }

                    public void onResponse(Call call, Response rsp)
                        throws IOException {
                        try {
                            String responseBody = rsp.body().string();
                            final int responseCode = rsp.code();

                            Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    debug.console(
                                        "[AI] Response code: " + responseCode
                                    );

                                    if (responseCode != 200) {
                                        debug.console(
                                            "[AI] Error response: " +
                                            responseBody
                                        );
                                        future.complete("");
                                        return;
                                    }

                                    try {
                                        JSONObject json = new JSONObject(
                                            responseBody
                                        );
                                        if (
                                            !json.has("choices") ||
                                            json
                                                .getJSONArray("choices")
                                                .length() ==
                                            0
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
                                });
                        } catch (Exception ex) {
                            Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    debug.console(
                                        "[AI] Response reading error: " +
                                        ex.getMessage()
                                    );
                                    future.complete("");
                                });
                        }
                    }
                }
            );
    }

    // Helper class to store pending requests in the queue
    private class PendingRequest {

        final String prompt;
        final CompletableFuture<String> future;
        final int itemCount;
        final String forcedModel;

        PendingRequest(
            String prompt,
            CompletableFuture<String> future,
            int itemCount
        ) {
            this.prompt = prompt;
            this.future = future;
            this.itemCount = itemCount;
            this.forcedModel = null;
        }

        PendingRequest(
            String prompt,
            CompletableFuture<String> future,
            String forcedModel
        ) {
            this.prompt = prompt;
            this.future = future;
            this.itemCount = 0;
            this.forcedModel = forcedModel;
        }
    }
}
