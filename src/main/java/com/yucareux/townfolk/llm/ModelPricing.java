package com.yucareux.townfolk.llm;

import java.util.Map;

/**
 * Per-model token pricing in USD per million tokens. Used to convert raw
 * token counts into a rough cost estimate for the admin UI.
 *
 * Values reflect OpenRouter pricing for the listed models at the time of
 * writing. Drift over time is fine — the displayed cost is "approximate"
 * by design. A future iteration can replace this table by fetching
 * {@code /v1/models} from OpenRouter and caching the pricing fields.
 *
 * Unknown models fall back to a conservative middle-of-the-road estimate
 * so the UI never shows $0.00 for paid work.
 */
public final class ModelPricing {

   public record Pricing(double inputPerMillion, double outputPerMillion) {
   }

   private static final Pricing FALLBACK = new Pricing(0.50, 1.50);

   private static final Map<String, Pricing> PRICES = Map.ofEntries(
      // DeepSeek
      // The ":free" variant on OpenRouter is rate-limited but $0/M.
      Map.entry("deepseek/deepseek-v4-flash:free", new Pricing(0.0, 0.0)),
      // v4-flash is DeepSeek's small / cheap tier — adjust if OpenRouter
      // pricing drifts. Estimate is conservative; real charge is per
      // OpenRouter at call time.
      Map.entry("deepseek/deepseek-v4-flash", new Pricing(0.14, 0.28)),
      Map.entry("deepseek/deepseek-chat-v3.1", new Pricing(0.27, 1.10)),
      Map.entry("deepseek/deepseek-chat", new Pricing(0.27, 1.10)),
      Map.entry("deepseek/deepseek-r1", new Pricing(0.55, 2.19)),
      // Anthropic
      Map.entry("anthropic/claude-haiku-4.5", new Pricing(0.25, 1.25)),
      Map.entry("anthropic/claude-sonnet-4.5", new Pricing(3.00, 15.00)),
      Map.entry("anthropic/claude-sonnet-4.6", new Pricing(3.00, 15.00)),
      // OpenAI
      Map.entry("openai/gpt-4o-mini", new Pricing(0.15, 0.60)),
      // Google
      Map.entry("google/gemini-2.5-flash", new Pricing(0.075, 0.30)),
      Map.entry("google/gemma-4-31b-it:free", new Pricing(0.0, 0.0)),
      // Gemma 4 31B paid tier on OpenRouter. Pricing estimate — adjust if
      // OpenRouter rate drifts.
      Map.entry("google/gemma-4-31b-it", new Pricing(0.20, 0.40))
   );

   public static double estimateCostUsd(String model, long inputTokens, long outputTokens) {
      Pricing p = PRICES.getOrDefault(model, FALLBACK);
      return (inputTokens / 1_000_000.0) * p.inputPerMillion()
         + (outputTokens / 1_000_000.0) * p.outputPerMillion();
   }

   private ModelPricing() {
   }
}
