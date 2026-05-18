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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async embedding client targeting OpenRouter's OpenAI-compatible
 * /v1/embeddings endpoint. Default model: text-embedding-3-small @ 512 dims.
 *
 * Lighter-weight than the chat client (no temperature, no token budgets):
 * input → fixed-length float vector, L2-normalised by us at result time so
 * retrieval can use a plain dot product.
 *
 * Failure mode: returns an empty array. Callers treat that as "vector not
 * ready yet" and skip the entry from retrieval (it'll be re-queued on the
 * next tick that touches MemoryStore).
 */
public final class EmbeddingClient {

   public static final int DIM = 512;
   private static final String MODEL = "openai/text-embedding-3-small";

   private static volatile EmbeddingClient instance;

   private final HttpClient http;
   private final Executor executor;
   private final Gson gson;

   private EmbeddingClient() {
      this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
         private final AtomicInteger n = new AtomicInteger();
         @Override
         public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "townfolk-embed-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
         }
      });
      this.gson = new Gson();
   }

   public static EmbeddingClient get() {
      EmbeddingClient local = instance;
      if (local == null) {
         synchronized (EmbeddingClient.class) {
            local = instance;
            if (local == null) {
               local = new EmbeddingClient();
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

   public CompletableFuture<float[]> embed(String text) {
      if (!isConfigured() || text == null || text.isBlank()) {
         return CompletableFuture.completedFuture(new float[0]);
      }
      return CompletableFuture.supplyAsync(() -> doEmbed(text), this.executor);
   }

   private float[] doEmbed(String text) {
      JsonObject body = new JsonObject();
      body.addProperty("model", MODEL);
      body.addProperty("input", text);
      body.addProperty("dimensions", DIM);

      String url = TownfolkConfig.COMMON.baseUrl.get().replaceAll("/$", "") + "/embeddings";
      HttpRequest req = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Authorization", "Bearer " + TownfolkConfig.COMMON.apiKey.get())
         .header("Content-Type", "application/json")
         .header("HTTP-Referer", TownfolkConfig.COMMON.httpReferer.get())
         .header("X-Title", TownfolkConfig.COMMON.appTitle.get())
         .timeout(Duration.ofSeconds(20))
         .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body), StandardCharsets.UTF_8))
         .build();
      try {
         HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
         if (resp.statusCode() != 200) {
            Townfolk.LOGGER.warn("Embeddings HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
            return new float[0];
         }
         JsonObject parsed = this.gson.fromJson(resp.body(), JsonObject.class);
         JsonArray data = parsed.getAsJsonArray("data");
         if (data == null || data.isEmpty()) return new float[0];
         JsonArray emb = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
         if (emb == null || emb.size() != DIM) {
            Townfolk.LOGGER.warn("Embeddings: unexpected dim={} (wanted {})", emb == null ? -1 : emb.size(), DIM);
            return new float[0];
         }
         float[] v = new float[DIM];
         double norm = 0;
         for (int i = 0; i < DIM; i++) {
            v[i] = emb.get(i).getAsFloat();
            norm += v[i] * v[i];
         }
         norm = Math.sqrt(norm);
         if (norm > 0) {
            for (int i = 0; i < DIM; i++) v[i] /= (float) norm;
         }
         return v;
      } catch (Throwable t) {
         Townfolk.LOGGER.warn("Embedding call failed", t);
         return new float[0];
      }
   }

   private static String truncate(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max) + "...";
   }
}
