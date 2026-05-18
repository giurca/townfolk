package com.yucareux.townfolk.town;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level "how much do we want" policy for produced resources,
 * scoped to a specific town.
 *
 * <p>Hysteresis model: each item has a {@code min} (resume threshold)
 * and {@code max} (stop threshold). A transient {@code active} flag
 * tracks whether villagers are currently producing this item:
 *
 * <ul>
 *   <li>Stock crosses above {@code max} → flip {@code active} to
 *       false. Production stops.</li>
 *   <li>Stock falls below {@code min} → flip {@code active} to
 *       true. Production resumes.</li>
 *   <li>Stock in the {@code [min, max]} band → state is held. Avoids
 *       twitching at the boundary.</li>
 * </ul>
 *
 * <p><b>Town scope.</b> Keyed by {@code (townSquarePos packed long,
 * itemId)} — multiple towns in the same dimension have independent
 * policies. Pre-fix this was {@code itemId}-only, which meant any
 * second town in the dimension shared the first town's caps.
 *
 * <p>The registry only stores entries the player has explicitly set;
 * any item not in the registry is treated as uncapped.
 *
 * <p>Persisted as {@link SavedData} on the level so policies survive
 * restarts.
 */
public final class ProductionTargets extends SavedData {

   public static final String STORAGE_KEY = Townfolk.MODID + "_production_targets";

   /** Composite key — same item id can have different policies in
    *  different towns within the same dimension. */
   public record Key(long townSquarePos, String itemId) {}

   /** One row in the production-cap policy. */
   public record Target(int min, int max, boolean active) {
      public Target {
         min = Math.max(0, min);
         max = Math.max(min, max);
      }
      public static Target of(int min, int max) { return new Target(min, max, true); }
   }

   /** No baked-in defaults — every item is uncapped until the player
    *  explicitly creates a target via the Resources tab. */

   private final Map<Key, Target> byKey = new LinkedHashMap<>();

   private ProductionTargets() {}

   public static ProductionTargets get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(
         new SavedData.Factory<>(ProductionTargets::new, ProductionTargets::load, null),
         STORAGE_KEY);
   }

   /** Lookup the live target for an item in a given town. */
   public static Target find(ServerLevel level, BlockPos townSquarePos, String itemId) {
      if (townSquarePos == null || itemId == null) return null;
      Target stored = get(level).byKey.get(new Key(townSquarePos.asLong(), itemId));
      return stored;
   }

   /** Overwrite the target for an item in a given town. Persisted. */
   public static void put(ServerLevel level, BlockPos townSquarePos, String itemId, Target t) {
      if (townSquarePos == null || itemId == null || t == null) return;
      ProductionTargets reg = get(level);
      reg.byKey.put(new Key(townSquarePos.asLong(), itemId), t);
      reg.setDirty();
      VerboseLog.write("PRODUCTION_TARGET_SET",
         "town=" + townSquarePos.toShortString() + " item=" + itemId
            + " min=" + t.min() + " max=" + t.max() + " active=" + t.active(),
         "");
   }

   /** Set min/max and immediately re-evaluate the active flag against
    *  the current stockpile. Without this re-eval, a player widening
    *  the band (e.g. raising max above current stock when active was
    *  false) would leave production paused until stock dropped below
    *  the new min — surprising behaviour (audit item F). */
   public static void setBounds(ServerLevel level, BlockPos townSquarePos, String itemId,
                                 int min, int max) {
      int currentStock = TownTreasury.totalOf(level, itemId);
      // Compute the correct active state directly from the new bounds
      // and current stock, rather than carrying over the stale flag.
      boolean active;
      if (currentStock >= max)      active = false;       // above cap → paused
      else if (currentStock < min)  active = true;        // below resume → producing
      else {
         // Stock sits inside the band — preserve previous state if any.
         Target current = find(level, townSquarePos, itemId);
         active = current == null ? true : current.active();
      }
      put(level, townSquarePos, itemId, new Target(min, max, active));
   }

   /** Remove the policy — production becomes uncapped again. */
   public static void forget(ServerLevel level, BlockPos townSquarePos, String itemId) {
      if (townSquarePos == null || itemId == null) return;
      ProductionTargets reg = get(level);
      if (reg.byKey.remove(new Key(townSquarePos.asLong(), itemId)) != null) {
         reg.setDirty();
         VerboseLog.write("PRODUCTION_TARGET_REMOVE",
            "town=" + townSquarePos.toShortString() + " item=" + itemId, "");
      }
   }

   /** Stockpile-aware production gate.
    *
    *  <p>Hysteresis lives here. Returns true if villagers should keep
    *  producing this item in this town. Side-effect: flips active
    *  when stock crosses min / max watermarks. */
   public static boolean shouldProduce(ServerLevel level, BlockPos townSquarePos,
                                       String itemId, int currentStock) {
      Target t = find(level, townSquarePos, itemId);
      if (t == null) return true;
      if (currentStock >= t.max() && t.active()) {
         put(level, townSquarePos, itemId, new Target(t.min(), t.max(), false));
         VerboseLog.write("PRODUCTION_PAUSE",
            "town=" + townSquarePos.toShortString() + " item=" + itemId
               + " stock=" + currentStock + " max=" + t.max(), "");
         return false;
      }
      if (currentStock < t.min() && !t.active()) {
         put(level, townSquarePos, itemId, new Target(t.min(), t.max(), true));
         VerboseLog.write("PRODUCTION_RESUME",
            "town=" + townSquarePos.toShortString() + " item=" + itemId
               + " stock=" + currentStock + " min=" + t.min(), "");
         return true;
      }
      return t.active();
   }

   /** Snapshot of the live policy for ONE town, suitable for sending
    *  to the client. */
   public static Map<String, Target> snapshot(ServerLevel level, BlockPos townSquarePos) {
      Map<String, Target> out = new LinkedHashMap<>();
      long key = townSquarePos.asLong();
      for (var e : get(level).byKey.entrySet()) {
         if (e.getKey().townSquarePos() == key) {
            out.put(e.getKey().itemId(), e.getValue());
         }
      }
      return out;
   }

   // ───── persistence ─────

   @Override
   public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
      ListTag list = new ListTag();
      for (var e : byKey.entrySet()) {
         CompoundTag ec = new CompoundTag();
         ec.putLong("town", e.getKey().townSquarePos());
         ec.putString("id", e.getKey().itemId());
         ec.putInt("min", e.getValue().min());
         ec.putInt("max", e.getValue().max());
         ec.putBoolean("active", e.getValue().active());
         list.add(ec);
      }
      tag.put("entries", list);
      return tag;
   }

   private static ProductionTargets load(CompoundTag tag, HolderLookup.Provider registries) {
      ProductionTargets reg = new ProductionTargets();
      if (!tag.contains("entries")) return reg;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         CompoundTag ec = list.getCompound(i);
         // Backwards compat: pre-town-scope entries had no "town" key.
         // They're treated as town=0 (unscoped) and the next player
         // edit will rewrite them with a real town pos.
         long town = ec.contains("town") ? ec.getLong("town") : 0L;
         reg.byKey.put(new Key(town, ec.getString("id")),
            new Target(ec.getInt("min"), ec.getInt("max"), ec.getBoolean("active")));
      }
      return reg;
   }
}
