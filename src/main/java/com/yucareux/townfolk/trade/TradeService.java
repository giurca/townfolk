package com.yucareux.townfolk.trade;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownAuxiliaryType;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the Trade tab's content:
 *
 * <ol>
 *   <li>Item discovery — periodically scan each loaded town's registered
 *       storage containers and grow its {@code discoveredItems} set.
 *       Trade rolls filter their archetype preference list against this
 *       set, so a brand-new town doesn't see "32 iron axes" trades.
 *   <li>Daily offer generation — once per game day per town with at
 *       least one Trade Post, mark expired offers (applying their tier's
 *       prestige penalty) and roll a new offer if today's slot is
 *       still empty.
 * </ol>
 *
 * <p>Both pieces live in one event subscriber, throttled cheaply on
 * gametime modulo. No per-frame work.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TradeService {

   private TradeService() {}

   /** Run the discovery scan once every this many ticks (1 Hz). */
   private static final int DISCOVERY_INTERVAL_TICKS = 20;

   /** Run the daily-generation check this often. We don't care about
    *  exact alignment with vanilla day rollover — we just check
    *  "has the day number advanced past lastGeneratedDay?" every few
    *  seconds. Once per minute is plenty. */
   private static final int DAILY_CHECK_INTERVAL_TICKS = 20 * 60;

   /** Days lookback for "today's offer already rolled." Stored as a
    *  custom field on TownData — see {@link #lastGeneratedDay}. */
   private static final java.util.WeakHashMap<TownData, Long> LAST_GENERATED_DAY =
      new java.util.WeakHashMap<>();

   @SubscribeEvent
   public static void onLevelTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      long now = level.getGameTime();

      boolean doDiscovery = now % DISCOVERY_INTERVAL_TICKS == 0L;
      boolean doDaily     = now % DAILY_CHECK_INTERVAL_TICKS == 0L;
      if (!doDiscovery && !doDaily) return;

      long currentDay = level.getDayTime() / 24000L;

      for (TownSquareBlockEntity ts : TownSquareBlockEntity.loadedIn(level)) {
         TownData data = ts.getTown();
         if (doDiscovery) discover(level, ts, data);
         if (doDaily)     rollDaily(level, ts, data, currentDay);
      }
   }

   // ───── Discovery ─────

   private static void discover(ServerLevel level, TownSquareBlockEntity ts, TownData data) {
      // Walk every registered storage container. Cheap — the registry
      // is keyed by packed pos and we only read item-ids.
      boolean changed = false;
      for (var entry : com.yucareux.townfolk.town.StorageRegistry.entries(level)) {
         net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(entry.getKey());
         // Only register items from storage that's inside this town's
         // coverage — otherwise a distant town's containers could
         // "discover" items for this one.
         if (!ts.coverage().contains(pos)) continue;
         var be = level.getBlockEntity(pos);
         if (!(be instanceof net.minecraft.world.Container c)) continue;
         for (int i = 0; i < c.getContainerSize(); i++) {
            var s = c.getItem(i);
            if (s.isEmpty()) continue;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id == null) continue;
            if (data.discoverItem(id.toString())) changed = true;
         }
      }
      if (changed) ts.setChanged();
   }

   // ───── Daily generation + expiry ─────

   private static void rollDaily(ServerLevel level, TownSquareBlockEntity ts, TownData data, long currentDay) {
      // Skip towns without a Trade Post. The auxiliary registry already
      // tracks this — no Trade Post → no trade activity at all.
      if (!data.hasAuxiliaryOfType(TownAuxiliaryType.TRADE_POST)) return;

      // 1) Expire offers whose expireDay has passed.
      boolean changed = false;
      List<TradeOffer> toUpdate = new ArrayList<>();
      for (TradeOffer o : data.tradeOffers()) {
         if (!o.isActive()) continue;
         if (currentDay > o.expireDay()) {
            toUpdate.add(o.withStatus(TradeOffer.STATUS_EXPIRED));
         }
      }
      for (TradeOffer expired : toUpdate) {
         data.replaceTradeOffer(expired);
         int delta = expired.tier().prestigeOnExpire;
         int after = data.addPrestige(delta);
         data.log().add(level.getGameTime(), TownLog.Level.INFO,
            expired.archetype().displayName()
               + "'s offer expired ("
               + expired.requestCount() + "× " + shortName(expired.requestItemId())
               + "). Prestige " + delta + " → " + after + ".");
         VerboseLog.write("TRADE_EXPIRED",
            "town=" + data.townName() + " offer=" + expired.id()
               + " item=" + expired.requestItemId()
               + " prestigeDelta=" + delta, "");
         changed = true;
      }

      // 2) Have we already rolled today's offer?
      Long last = LAST_GENERATED_DAY.get(data);
      if (last != null && last == currentDay) {
         if (changed) ts.setChanged();
         return;
      }
      // First-load fallback: if any active offer was created today,
      // count it as already-rolled (so a server restart doesn't
      // double-up).
      for (TradeOffer o : data.tradeOffers()) {
         if (o.isActive() && o.createdDay() == currentDay) {
            LAST_GENERATED_DAY.put(data, currentDay);
            if (changed) ts.setChanged();
            return;
         }
      }

      // 3) Roll a new offer.
      TradeOffer offer = rollOffer(data, currentDay, level.getRandom());
      if (offer != null) {
         data.addTradeOffer(offer);
         data.log().add(level.getGameTime(), TownLog.Level.INFO,
            offer.archetype().displayName() + " posted an offer: "
               + offer.requestCount() + "× " + shortName(offer.requestItemId())
               + " for " + offer.paymentEmeralds() + " emeralds (" + offer.tier().name() + ").");
         VerboseLog.write("TRADE_ROLLED",
            "town=" + data.townName() + " offer=" + offer.id()
               + " archetype=" + offer.archetype() + " tier=" + offer.tier()
               + " item=" + offer.requestItemId() + " count=" + offer.requestCount()
               + " pay=" + offer.paymentEmeralds(), "");
         changed = true;
         // Fire off LLM flavor generation. Async; updates the offer
         // in-place on the server thread when it lands. If LLM is
         // disabled (no api key) or errors out, the offer keeps its
         // empty blurb — the cell still renders fine without it.
         requestFlavor(level, ts, offer);
      }
      LAST_GENERATED_DAY.put(data, currentDay);
      if (changed) ts.setChanged();
   }

   /** Ask the LLM for a one-line flavor blurb for this offer. Cheap
    *  (~30 tokens out). Fire-and-forget: on result, drop the blurb
    *  onto the offer record via replaceTradeOffer (server thread). */
   private static void requestFlavor(ServerLevel level, TownSquareBlockEntity ts, TradeOffer offer) {
      String model = com.yucareux.townfolk.config.TownfolkConfig.COMMON.dialogueModel.get();
      String system = "You write very short flavor lines for fantasy trade offers. "
                    + "Reply with EXACTLY ONE sentence, under 25 words, no quotes. "
                    + "First-person from the visiting trader's POV, naming the item.";
      String user = "Visitor archetype: " + offer.archetype().displayName() + ". "
                  + "They want " + offer.requestCount() + "× "
                  + shortName(offer.requestItemId()) + " "
                  + "for " + offer.paymentEmeralds() + " emeralds. "
                  + "Tier: " + offer.tier().name().toLowerCase(java.util.Locale.ROOT) + ". "
                  + "Write a single line that reads like a notice they pinned to the town's "
                  + "Trade Post.";
      String townName = ts.getTown().townName();
      String offerId = offer.id();
      net.minecraft.server.MinecraftServer server = level.getServer();
      com.yucareux.townfolk.llm.LlmClient.get()
         .chat(model, system, user)
         .whenComplete((result, err) -> {
            if (err != null || result == null || !result.ok()) {
               VerboseLog.write("TRADE_FLAVOR_FAIL",
                  "town=" + townName + " offer=" + offerId
                     + " err=" + (err == null ? (result == null ? "?" : result.error()) : err.getMessage()),
                  "");
               return;
            }
            String blurb = result.content();
            if (blurb == null) return;
            blurb = blurb.trim();
            if (blurb.startsWith("\"") && blurb.endsWith("\"") && blurb.length() >= 2) {
               blurb = blurb.substring(1, blurb.length() - 1).trim();
            }
            if (blurb.length() > 240) blurb = blurb.substring(0, 240);
            final String fixedBlurb = blurb;
            // Apply on the server thread so we never race with the
            // TownData mutations in the daily tick.
            if (server == null) return;
            server.execute(() -> {
               var maybe = ts.getTown().findTradeOffer(offerId);
               if (maybe.isEmpty()) return;
               // Only stamp the blurb if the offer's still active —
               // don't ressurect a fulfilled/expired one.
               if (!maybe.get().isActive()) return;
               ts.getTown().replaceTradeOffer(maybe.get().withFlavor(fixedBlurb));
               ts.setChanged();
               VerboseLog.write("TRADE_FLAVOR_OK",
                  "town=" + townName + " offer=" + offerId, fixedBlurb);
            });
         });
   }

   /** Roll a single new offer. Returns null only if NO archetype has
    *  any items the town has discovered (brand-new town with empty
    *  stockpile and no Trade Post deposits yet — in which case we
    *  defer until something gets stockpiled). */
   private static TradeOffer rollOffer(TownData data, long currentDay, RandomSource rng) {
      // Tier choice — biased by prestige. At prestige 0, COMMON
      // dominates; PREMIUM is impossible until 200.
      TradeTier tier = pickTier(data.prestige(), rng);

      // Try a few archetypes; pick the first one whose preference list
      // intersects the town's discovered items.
      List<VisitorArchetype> shuffled = new ArrayList<>(List.of(VisitorArchetype.values()));
      // RandomSource doesn't plug into Collections.shuffle; do it by hand
      // (Fisher-Yates) so we use the deterministic level RNG.
      for (int i = shuffled.size() - 1; i > 0; i--) {
         int j = rng.nextInt(i + 1);
         VisitorArchetype tmp = shuffled.get(i);
         shuffled.set(i, shuffled.get(j));
         shuffled.set(j, tmp);
      }
      for (VisitorArchetype a : shuffled) {
         List<String> eligible = new ArrayList<>();
         for (String itemId : a.preferredItems()) {
            if (data.discoveredItems().contains(itemId)) eligible.add(itemId);
         }
         if (eligible.isEmpty()) continue;
         String itemId = eligible.get(rng.nextInt(eligible.size()));
         int qty = randInt(rng, tier.quantityMin, tier.quantityMax);
         int payment = Math.max(1, qty / 4) * tier.paymentMultiplier;
         long expireDay = currentDay + tier.expireDays;
         String id = UUID.randomUUID().toString().substring(0, 8);
         return new TradeOffer(id, a, tier, itemId, qty, payment, "",
            currentDay, expireDay, TradeOffer.STATUS_ACTIVE);
      }
      // Nothing fits — wait for the town to stockpile something.
      VerboseLog.write("TRADE_ROLL_DEFERRED",
         "town=" + data.townName()
            + " reason=no-archetype-overlap discovered=" + data.discoveredItems().size(),
         "");
      return null;
   }

   private static TradeTier pickTier(int prestige, RandomSource rng) {
      // Eligible tiers: those whose prestigeRequired ≤ current prestige.
      // Weights bias toward Common at low prestige.
      List<TradeTier> eligible = new ArrayList<>();
      for (TradeTier t : TradeTier.values()) {
         if (prestige >= t.prestigeRequired) eligible.add(t);
      }
      if (eligible.isEmpty()) return TradeTier.COMMON;
      // Simple weights: Common 6, Notable 3, Premium 1 (within eligible).
      int totalWeight = 0;
      int[] weights = new int[eligible.size()];
      for (int i = 0; i < eligible.size(); i++) {
         weights[i] = switch (eligible.get(i)) {
            case COMMON  -> 6;
            case NOTABLE -> 3;
            case PREMIUM -> 1;
         };
         totalWeight += weights[i];
      }
      int r = rng.nextInt(totalWeight);
      int cum = 0;
      for (int i = 0; i < eligible.size(); i++) {
         cum += weights[i];
         if (r < cum) return eligible.get(i);
      }
      return eligible.get(eligible.size() - 1);
   }

   private static int randInt(RandomSource rng, int min, int max) {
      if (max <= min) return min;
      return min + rng.nextInt(max - min + 1);
   }

   private static String shortName(String itemId) {
      int c = itemId.indexOf(':');
      return (c < 0 ? itemId : itemId.substring(c + 1)).replace('_', ' ');
   }
}
