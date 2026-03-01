package com.resonant.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoiceCoreClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Logger LOGGER = Logger.getLogger(VoiceCoreClient.class.getName());
    private static final int MAX_RETRIES = 2;
    private static final int[] RETRY_DELAYS_MS = new int[]{200, 500};
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final String httpUrl;
    private final TokenService tokenService;

    public record SessionInfo(UUID uuid, String sessionId, String serverId, long createdAt, long lastSeen, long accessExpiresAt, long refreshExpiresAt, long sessionExpiresAt, Long pingMs) {}
    public record SessionUpdate(String id, long ts, String type, UUID uuid, String serverId, String sessionId, String reason, Long pingMs) {}
    public record ActiveSessionsSnapshot(List<SessionInfo> sessions, long now) {}
    public record SessionUpdatesSnapshot(List<SessionUpdate> updates, long now) {}

    public VoiceCoreClient(String httpUrl, String secret) {
        this.httpUrl = httpUrl;
        this.tokenService = new TokenService(secret, 30);
        this.httpClient = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(10))
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(8))
                .retryOnConnectionFailure(true)
                .build();
    }

    public void sendEvent(UUID uuid, String name, String serverId, String type, JsonObject payload) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("serverId", serverId);
        body.add("payload", payload);
        Request request = new Request.Builder()
                .url(httpUrl + "/events")
                .addHeader("Authorization", "Bearer " + tokenService.createBridgeToken(serverId))
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                LOGGER.log(Level.WARNING, "voice-core events request failed", e);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                if (!response.isSuccessful()) {
                    LOGGER.warning("voice-core events response: " + response.code());
                }
                response.close();
            }
        });
    }

    public void registerPlayerCode(UUID uuid, String name, String serverId, String code, int ttlSeconds) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("serverId", serverId);
        body.addProperty("code", code);
        body.addProperty("ttlSeconds", ttlSeconds);
        Request request = new Request.Builder()
                .url(httpUrl + "/tokens")
                .addHeader("Authorization", "Bearer " + tokenService.createBridgeToken(serverId))
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                LOGGER.log(Level.WARNING, "voice-core tokens request failed", e);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                if (!response.isSuccessful()) {
                    LOGGER.warning("voice-core tokens response: " + response.code());
                }
                response.close();
            }
        });
    }

    public void fetchActiveSessions(String serverId, Consumer<ActiveSessionsSnapshot> onSuccess, Consumer<Throwable> onError) {
        fetchActiveSessionsInternal(serverId, onSuccess, onError, 0);
    }

    private void fetchActiveSessionsInternal(String serverId, Consumer<ActiveSessionsSnapshot> onSuccess, Consumer<Throwable> onError, int attempt) {
        String url = httpUrl + "/sessions/active?serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + tokenService.createBridgeToken(serverId))
                .addHeader("Connection", "close")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (shouldRetry(e, attempt)) {
                    scheduleRetry(() -> fetchActiveSessionsInternal(serverId, onSuccess, onError, attempt + 1), retryDelayMs(attempt));
                    return;
                }
                LOGGER.log(Level.WARNING, "voice-core sessions active request failed", e);
                if (onError != null) {
                    onError.accept(e);
                }
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        IOException error = new IOException("voice-core sessions active response: " + response.code());
                        LOGGER.warning(error.getMessage());
                        if (onError != null) {
                            onError.accept(error);
                        }
                        return;
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    JsonObject json = gson.fromJson(body, JsonObject.class);
                    List<SessionInfo> sessions = new ArrayList<>();
                    if (json != null && json.has("sessions")) {
                        for (var element : json.getAsJsonArray("sessions")) {
                            JsonObject entry = element.getAsJsonObject();
                            sessions.add(new SessionInfo(
                                    UUID.fromString(entry.get("uuid").getAsString()),
                                    entry.get("sessionId").getAsString(),
                                    entry.get("serverId").getAsString(),
                                    entry.get("createdAt").getAsLong(),
                                    entry.get("lastSeen").getAsLong(),
                                    entry.get("accessExpiresAt").getAsLong(),
                                    entry.get("refreshExpiresAt").getAsLong(),
                                    entry.get("sessionExpiresAt").getAsLong(),
                                    entry.has("pingMs") && !entry.get("pingMs").isJsonNull() ? entry.get("pingMs").getAsLong() : null
                            ));
                        }
                    }
                    long now = json != null && json.has("now") ? json.get("now").getAsLong() : System.currentTimeMillis();
                    if (onSuccess != null) {
                        onSuccess.accept(new ActiveSessionsSnapshot(sessions, now));
                    }
                } catch (Exception ex) {
                    if (ex instanceof IOException io && shouldRetry(io, attempt)) {
                        scheduleRetry(() -> fetchActiveSessionsInternal(serverId, onSuccess, onError, attempt + 1), retryDelayMs(attempt));
                        return;
                    }
                    LOGGER.log(Level.WARNING, "voice-core sessions active parse failed", ex);
                    if (onError != null) {
                        onError.accept(ex);
                    }
                }
            }
        });
    }

    public void fetchSessionUpdates(String serverId, long since, Consumer<SessionUpdatesSnapshot> onSuccess, Consumer<Throwable> onError) {
        fetchSessionUpdatesInternal(serverId, since, onSuccess, onError, 0);
    }

    private void fetchSessionUpdatesInternal(String serverId, long since, Consumer<SessionUpdatesSnapshot> onSuccess, Consumer<Throwable> onError, int attempt) {
        String url = httpUrl + "/sessions/updates?serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8) + "&since=" + since;
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + tokenService.createBridgeToken(serverId))
                .addHeader("Connection", "close")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (shouldRetry(e, attempt)) {
                    scheduleRetry(() -> fetchSessionUpdatesInternal(serverId, since, onSuccess, onError, attempt + 1), retryDelayMs(attempt));
                    return;
                }
                LOGGER.log(Level.WARNING, "voice-core sessions updates request failed", e);
                if (onError != null) {
                    onError.accept(e);
                }
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        IOException error = new IOException("voice-core sessions updates response: " + response.code());
                        LOGGER.warning(error.getMessage());
                        if (onError != null) {
                            onError.accept(error);
                        }
                        return;
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    JsonObject json = gson.fromJson(body, JsonObject.class);
                    List<SessionUpdate> updates = new ArrayList<>();
                    if (json != null && json.has("updates")) {
                        for (var element : json.getAsJsonArray("updates")) {
                            JsonObject entry = element.getAsJsonObject();
                            updates.add(new SessionUpdate(
                                    entry.get("id").getAsString(),
                                    entry.get("ts").getAsLong(),
                                    entry.get("type").getAsString(),
                                    UUID.fromString(entry.get("uuid").getAsString()),
                                    entry.get("serverId").getAsString(),
                                    entry.has("sessionId") && !entry.get("sessionId").isJsonNull() ? entry.get("sessionId").getAsString() : null,
                                    entry.has("reason") && !entry.get("reason").isJsonNull() ? entry.get("reason").getAsString() : null,
                                    entry.has("pingMs") && !entry.get("pingMs").isJsonNull() ? entry.get("pingMs").getAsLong() : null
                            ));
                        }
                    }
                    long now = json != null && json.has("now") ? json.get("now").getAsLong() : System.currentTimeMillis();
                    if (onSuccess != null) {
                        onSuccess.accept(new SessionUpdatesSnapshot(updates, now));
                    }
                } catch (Exception ex) {
                    if (ex instanceof IOException io && shouldRetry(io, attempt)) {
                        scheduleRetry(() -> fetchSessionUpdatesInternal(serverId, since, onSuccess, onError, attempt + 1), retryDelayMs(attempt));
                        return;
                    }
                    LOGGER.log(Level.WARNING, "voice-core sessions updates parse failed", ex);
                    if (onError != null) {
                        onError.accept(ex);
                    }
                }
            }
        });
    }

    private boolean shouldRetry(IOException error, int attempt) {
        if (attempt >= MAX_RETRIES) {
            return false;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unexpected end of stream") || lower.contains("eof");
    }

    private int retryDelayMs(int attempt) {
        int index = Math.min(attempt, RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    private void scheduleRetry(Runnable task, int delayMs) {
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(task);
    }
}
