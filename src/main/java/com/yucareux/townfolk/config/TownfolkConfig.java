package com.yucareux.townfolk.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side configuration for Townfolk's LLM integration.
 *
 * The API key lives in this file rather than world data so it doesn't leak
 * into level saves shared with other players. Empty string disables the LLM
 * layer cleanly — the mod still works in scaffold-only mode (commands,
 * Town Square persistence) without it.
 *
 * After first launch you'll find {@code config/townfolk-common.toml} in the
 * server's data directory; paste your OpenRouter key into
 * {@code openrouter.api_key}.
 */
public final class TownfolkConfig {

   public static final ModConfigSpec SPEC;
   public static final Common COMMON;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      COMMON = new Common(builder);
      SPEC = builder.build();
   }

   public static final class Common {
      public final ModConfigSpec.ConfigValue<String> apiKey;
      public final ModConfigSpec.ConfigValue<String> baseUrl;
      public final ModConfigSpec.ConfigValue<String> backstoryModel;
      public final ModConfigSpec.ConfigValue<String> dialogueModel;
      public final ModConfigSpec.IntValue requestTimeoutSeconds;
      public final ModConfigSpec.IntValue maxOutputTokens;
      public final ModConfigSpec.DoubleValue temperature;
      public final ModConfigSpec.ConfigValue<String> httpReferer;
      public final ModConfigSpec.ConfigValue<String> appTitle;

      Common(ModConfigSpec.Builder b) {
         b.comment("OpenRouter / DeepSeek client settings. Empty api_key disables LLM features.")
            .push("openrouter");

         this.apiKey = b.comment(
            "Your OpenRouter API key. Get one at https://openrouter.ai/keys.",
            "Leave empty to run the mod in scaffold-only mode (no LLM calls)."
         ).define("api_key", "");

         this.baseUrl = b.comment(
            "OpenRouter API base URL. Override only if you're routing through a proxy",
            "or hitting a different provider with an OpenAI-compatible endpoint."
         ).define("base_url", "https://openrouter.ai/api/v1");

         this.backstoryModel = b.comment(
            "Model id used for one-off heavyweight generations (backstory at spawn,",
            "major story events). DeepSeek's reasoner punches above its price tier",
            "for narrative work."
         ).define("backstory_model", "google/gemma-4-31b-it");

         this.dialogueModel = b.comment(
            "Model id used for routine think cycles and player dialogue. Same model",
            "is fine; cheaper alternatives also work."
         ).define("dialogue_model", "google/gemma-4-31b-it");

         this.requestTimeoutSeconds = b.comment(
            "Hard cap on a single API call. Beyond this the request is cancelled",
            "and the villager keeps their previous state."
         ).defineInRange("request_timeout_seconds", 30, 5, 300);

         this.maxOutputTokens = b.comment(
            "Cap on tokens the model is allowed to emit per response."
         ).defineInRange("max_output_tokens", 400, 50, 4000);

         this.temperature = b.comment(
            "Sampling temperature. 0.0 deterministic, 1.0 standard, >1.0 wild."
         ).defineInRange("temperature", 0.85, 0.0, 2.0);

         this.httpReferer = b.comment(
            "Optional HTTP-Referer header sent to OpenRouter; helps them attribute",
            "usage to your app. Leave blank if you don't care."
         ).define("http_referer", "https://github.com/giurca/townfolk");

         this.appTitle = b.comment(
            "Optional X-Title header sent to OpenRouter for the same reason."
         ).define("app_title", "Tellus Townfolk");

         b.pop();

         b.comment("World-behaviour switches that affect how villagers act in the world.")
            .push("world");

         this.peaceful = b.comment(
            "When true, all violent actions ([ACTION: attack], [ACTION: defend], etc.)",
            "are no-op stubs that only write memory + log. The LLM still knows the",
            "verbs exist, but they never deal damage. Flip to false when you're ready",
            "for combat."
         ).define("peaceful", true);

         b.pop();

         b.comment("Autonomy: villagers occasionally 'think' between commitments and decide",
            "what to do on their own. Strict budget + cooldown keeps cost bounded.")
            .push("autonomy");

         this.autonomyEnabled = b.comment(
            "Master switch. When false, villagers act only when prompted by the",
            "player, the schedule, or another villager (exchanges)."
         ).define("enabled", true);

         this.autonomyIntervalTicks = b.comment(
            "Base think-cadence per villager, in game ticks (20 = 1 second).",
            "Default 1800 = 90 seconds. Villagers are staggered across ticks so",
            "you don't see N simultaneous LLM calls."
         ).defineInRange("interval_ticks", 1800, 200, 24000);

         this.autonomyCooldownTicks = b.comment(
            "After a successful autonomy action, skip thinking for this many ticks.",
            "Prevents the LLM from repeating itself in tight loops."
         ).defineInRange("cooldown_ticks", 3600, 0, 48000);

         this.autonomyDailyBudget = b.comment(
            "Maximum autonomy LLM calls per villager per in-game day.",
            "Hard cap — once exhausted, the villager goes silent until day rollover."
         ).defineInRange("daily_budget", 20, 0, 200);

         b.pop();
      }

      public final ModConfigSpec.ConfigValue<Boolean> peaceful;
      public final ModConfigSpec.ConfigValue<Boolean> autonomyEnabled;
      public final ModConfigSpec.IntValue autonomyIntervalTicks;
      public final ModConfigSpec.IntValue autonomyCooldownTicks;
      public final ModConfigSpec.IntValue autonomyDailyBudget;
   }

   private TownfolkConfig() {
   }
}
