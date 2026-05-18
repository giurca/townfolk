package com.yucareux.townfolk.llm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.config.TownfolkConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight non-chat OpenRouter client. Currently exposes only the
 * {@code /v1/credits} endpoint, used by the admin UI to show your account's
 * total spend.
 *
 * Shape of the response:
 *   { "data": { "total_credits": <number>, "total_usage": <number> } }
 * Older keys may also surface "limit" / "usage" at top level — we handle
 * both shapes by sniffing the response.
 */
public final class OpenRouterClient {

   private static volatile OpenRouterClient instance;

   private final HttpClient http;
   private final java.util.concurrent.Executor executor;
   private final Gson gson = new Gson();

   private OpenRouterClient() {
      this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      this.executor = Executors.newFixedThreadPool(1, new ThreadFactory() {
         private final AtomicInteger n = new AtomicInteger();
         @Override
         public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "townfolk-openrouter-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
         }
      });
   }

   public static OpenRouterClient get() {
      OpenRouterClient local = instance;
      if (local == null) {
         synchronized (OpenRouterClient.class) {
            local = instance;
            if (local == null) {
               local = new OpenRouterClient();
               instance = local;
            }
         }
      }
      return local;
   }

   public CompletableFuture<CreditStatus> fetchCredits() {
      String key = TownfolkConfig.COMMON.apiKey.get();
      if (key == null || key.isBlank()) {
         return CompletableFuture.completedFuture(CreditStatus.disabled());
      }
      return CompletableFuture.supplyAsync(this::doFetch, this.executor);
   }

   private CreditStatus doFetch() {
      String url = TownfolkConfig.COMMON.baseUrl.get().replaceAll("/$", "") + "/credits";
      HttpRequest req = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Authorization", "Bearer " + TownfolkConfig.COMMON.apiKey.get())
         .header("Content-Type", "application/json")
         .header("HTTP-Referer", TownfolkConfig.COMMON.httpReferer.get())
         .header("X-Title", TownfolkConfig.COMMON.appTitle.get())
         .timeout(Duration.ofSeconds(15))
         .GET()
         .build();
      try {
         HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
         if (resp.statusCode() != 200) {
            Townfolk.LOGGER.warn("OpenRouter /credits HTTP {}: {}", resp.statusCode(), resp.body());
            return CreditStatus.error(resp.statusCode(), resp.body());
         }
         JsonObject parsed = this.gson.fromJson(resp.body(), JsonObject.class);
         JsonObject data = parsed.has("data") && parsed.get("data").isJsonObject()
            ? parsed.getAsJsonObject("data") : parsed;
         Double totalCredits = readDouble(data, "total_credits", "limit");
         Double totalUsage = readDouble(data, "total_usage", "usage");
         return CreditStatus.ok(
            Optional.ofNullable(totalCredits),
            Optional.ofNullable(totalUsage)
         );
      } catch (Throwable t) {
         Townfolk.LOGGER.warn("OpenRouter /credits call failed", t);
         return CreditStatus.error(-1, t.getMessage());
      }
   }

   private Double readDouble(JsonObject obj, String... keys) {
      for (String k : keys) {
         JsonElement e = obj.get(k);
         if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) {
            try { return e.getAsDouble(); } catch (Throwable ignore) { /* not a number */ }
         }
      }
      return null;
   }

   public record CreditStatus(boolean ok, Optional<Double> totalCredits,
                              Optional<Double> totalUsage, int httpStatus, String error) {
      public static CreditStatus ok(Optional<Double> credits, Optional<Double> usage) {
         return new CreditStatus(true, credits, usage, 200, null);
      }
      public static CreditStatus error(int status, String err) {
         return new CreditStatus(false, Optional.empty(), Optional.empty(), status, err);
      }
      public static CreditStatus disabled() {
         return new CreditStatus(false, Optional.empty(), Optional.empty(), 0, "api_key not configured");
      }
   }

}
