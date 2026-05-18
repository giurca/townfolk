package com.yucareux.townfolk.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.config.TownfolkConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal OpenAI-format chat client targeting OpenRouter. Shared singleton —
 * one HttpClient, one bounded executor — so 30 villagers thinking at once
 * don't spin up arbitrary threads.
 *
 * Returns {@link LlmResult} via CompletableFuture; callers attach
 * {@code thenAcceptAsync(..., server)} to apply results on the server thread.
 *
 * No retries, no fallbacks. If a request fails we return an empty payload and
 * let the caller decide what to do. (For backstory generation: keep the
 * empty backstory and try again next time the villager is referenced.)
 */
public final class LlmClient {

   private static volatile LlmClient instance;

   private final HttpClient http;
   private final Executor executor;
   private final Gson gson;

   private LlmClient() {
      this.http = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(10))
         .build();
      this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
         private final AtomicInteger n = new AtomicInteger();
         @Override
         public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "townfolk-llm-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
         }
      });
      this.gson = new Gson();
   }

   public static LlmClient get() {
      LlmClient local = instance;
      if (local == null) {
         synchronized (LlmClient.class) {
            local = instance;
            if (local == null) {
               local = new LlmClient();
               instance = local;
            }
         }
      }
      return local;
   }

   public boolean isConfigured() {
      String key = TownfolkConfig.COMMON.apiKey.get();
      return key != null && !key.isBlank();
   }

   public CompletableFuture<LlmResult> chat(String model, String systemPrompt, String userPrompt) {
      return chat(model, List.of(
         new Message("system", systemPrompt),
         new Message("user", userPrompt)
      ));
   }

   public CompletableFuture<LlmResult> chat(String model, List<Message> messages) {
      if (!isConfigured()) {
         return CompletableFuture.completedFuture(LlmResult.disabled());
      }
      return CompletableFuture.supplyAsync(() -> doChat(model, messages), this.executor);
   }

   private LlmResult doChat(String model, List<Message> messages) {
      JsonObject body = new JsonObject();
      body.addProperty("model", model);
      body.addProperty("temperature", TownfolkConfig.COMMON.temperature.get());
      body.addProperty("max_tokens", TownfolkConfig.COMMON.maxOutputTokens.get());
      JsonArray msgs = new JsonArray();
      for (Message m : messages) {
         JsonObject mo = new JsonObject();
         mo.addProperty("role", m.role());
         mo.addProperty("content", m.content());
         msgs.add(mo);
      }
      body.add("messages", msgs);

      String url = TownfolkConfig.COMMON.baseUrl.get().replaceAll("/$", "") + "/chat/completions";
      HttpRequest req = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Authorization", "Bearer " + TownfolkConfig.COMMON.apiKey.get())
         .header("Content-Type", "application/json")
         .header("HTTP-Referer", TownfolkConfig.COMMON.httpReferer.get())
         .header("X-Title", TownfolkConfig.COMMON.appTitle.get())
         .timeout(Duration.ofSeconds(TownfolkConfig.COMMON.requestTimeoutSeconds.get()))
         .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body), StandardCharsets.UTF_8))
         .build();

      try {
         HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
         if (resp.statusCode() != 200) {
            Townfolk.LOGGER.warn("OpenRouter HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 500));
            return LlmResult.error(resp.statusCode(), resp.body());
         }
         JsonObject parsed = this.gson.fromJson(resp.body(), JsonObject.class);
         JsonArray choices = parsed.getAsJsonArray("choices");
         if (choices == null || choices.isEmpty()) {
            return LlmResult.error(200, "no choices in response");
         }
         String content = choices.get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();
         long inputTokens = 0;
         long outputTokens = 0;
         if (parsed.has("usage")) {
            JsonObject usage = parsed.getAsJsonObject("usage");
            if (usage.has("prompt_tokens")) inputTokens = usage.get("prompt_tokens").getAsLong();
            if (usage.has("completion_tokens")) outputTokens = usage.get("completion_tokens").getAsLong();
         }
         return LlmResult.ok(content.trim(), model, inputTokens, outputTokens);
      } catch (Throwable t) {
         Townfolk.LOGGER.warn("LLM call failed", t);
         return LlmResult.error(-1, t.getMessage());
      }
   }

   private static String truncate(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max) + "...";
   }

   public record Message(String role, String content) {
   }

   public record LlmResult(boolean ok, String content, int httpStatus, String error,
                           String model, long inputTokens, long outputTokens) {
      public static LlmResult ok(String content, String model, long inputTokens, long outputTokens) {
         return new LlmResult(true, content, 200, null, model, inputTokens, outputTokens);
      }
      public static LlmResult error(int status, String err) {
         return new LlmResult(false, "", status, err, "", 0L, 0L);
      }
      public static LlmResult disabled() {
         return new LlmResult(false, "", 0, "api_key not configured", "", 0L, 0L);
      }
   }
}
