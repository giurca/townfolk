package com.yucareux.townfolk.town;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level persistent registry of player-configured storage containers.
 * Replaces the old auto-discovery {@code TownStorageLedger} model: a
 * villager will only deposit-into or withdraw-from a chest that the
 * player has explicitly registered via the sneak+empty-hand-right-click
 * configuration popup.
 *
 * Keyed by packed {@code BlockPos.asLong()} so positions across
 * different worlds don't collide (each world has its own SavedData).
 * The values are {@link StorageConfig}s carrying the filter mode + 16
 * filter slots + bookkeeping.
 */
public final class StorageRegistry extends SavedData {

   public static final String STORAGE_KEY = Townfolk.MODID + "_storage_registry";

   private final Map<Long, StorageConfig> byPos = new LinkedHashMap<>();

   /** Mutation counter. Bumped on every put/forget/touch — invalidates
    *  the {@link #tagIndex} below so callers always see a fresh view
    *  within one mutation cycle. Volatile so the index-rebuild thread
    *  sees writes from the touch-thread without a fence. */
   private volatile long mutationGen = 0L;

   /** Cached tag → registered-positions reverse index. Lazily rebuilt
    *  in {@link #tilesForTag} whenever the cached generation lags the
    *  current {@link #mutationGen}. Stage 21a — kills the
    *  audit-identified 4M-op-per-meal-window scan in MealService by
    *  turning each tier check from O(barrels × slots) into
    *  O(matching barrels). */
   private record IndexEntry(long gen, Set<Long> positions) {}
   private final Map<TagKey<Item>, IndexEntry> tagIndex = new ConcurrentHashMap<>();

   private StorageRegistry() {}

   public static StorageRegistry get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(
         new SavedData.Factory<>(StorageRegistry::new, StorageRegistry::load, null),
         STORAGE_KEY);
   }

   /** Register or update a container's config. Idempotent. */
   public static void put(ServerLevel level, BlockPos pos, StorageConfig config) {
      StorageRegistry reg = get(level);
      boolean isNew = !reg.byPos.containsKey(pos.asLong());
      reg.byPos.put(pos.asLong(), config);
      reg.mutationGen++;
      reg.setDirty();
      VerboseLog.write(isNew ? "STORAGE_REGISTER" : "STORAGE_UPDATE",
         "pos=" + pos.toShortString()
            + " mode=" + config.mode()
            + " filter=" + config.filterCount() + "/" + StorageConfig.FILTER_SLOTS
            + " label=\"" + config.label() + "\"", "");
   }

   /** Drop a container — usually because it was broken. */
   public static void forget(ServerLevel level, BlockPos pos) {
      StorageRegistry reg = get(level);
      if (reg.byPos.remove(pos.asLong()) != null) {
         reg.mutationGen++;
         reg.setDirty();
         VerboseLog.write("STORAGE_FORGET", "pos=" + pos.toShortString(), "");
      }
   }

   /** Lookup a single config. Returns null if the position isn't registered. */
   public static StorageConfig find(ServerLevel level, BlockPos pos) {
      return get(level).byPos.get(pos.asLong());
   }

   /** Read-only view of every registered entry. */
   public static Collection<Map.Entry<Long, StorageConfig>> entries(ServerLevel level) {
      return java.util.List.copyOf(get(level).byPos.entrySet());
   }

   public static int size(ServerLevel level) { return get(level).byPos.size(); }

   /** Convenience: is this exact position registered? */
   public static boolean isRegistered(ServerLevel level, BlockPos pos) {
      return get(level).byPos.containsKey(pos.asLong());
   }

   /** Resolve a player-set label hint (e.g. "wool stash") to a specific
    *  registered container position. Comparison is case-insensitive and
    *  uses exact match first, then a one-directional substring match
    *  ({@code needle ⊆ label}). The other direction ({@code label ⊆
    *  needle}) was previously allowed but caused a barrel labelled
    *  "a" to match any sentence containing "a". Ties broken by nearest
    *  to {@code near}.
    *
    *  Needle must be at least {@link #MIN_LABEL_NEEDLE_CHARS} characters
    *  to be eligible for substring matching, again to prevent
    *  trivial-prefix collisions. Exact matches bypass the min-length
    *  check (player typed the full name on purpose).
    *
    *  Returns {@code null} if no labelled container matches.
    *
    *  Used by {@link com.yucareux.townfolk.world.ToolDispatcher} to let
    *  villagers route to barrels by name — i.e. when a verb like
    *  {@code [ACTION: deposit 8 wool in wool stash]} is emitted, this
    *  function turns the trailing "wool stash" into the BlockPos of the
    *  barrel the player named "wool stash". */
   private static final int MIN_LABEL_NEEDLE_CHARS = 3;

   public static BlockPos findByLabel(ServerLevel level, String hint, BlockPos near) {
      if (hint == null || hint.isBlank()) return null;
      String needle = hint.toLowerCase(java.util.Locale.ROOT).trim();
      if (needle.startsWith("\"") && needle.endsWith("\"") && needle.length() >= 2) {
         needle = needle.substring(1, needle.length() - 1).trim();
      }
      if (needle.startsWith("the ")) needle = needle.substring(4).trim();
      if (needle.isEmpty()) return null;
      BlockPos exact = null, substring = null;
      double bestExact = Double.MAX_VALUE, bestSub = Double.MAX_VALUE;
      for (var e : get(level).byPos.entrySet()) {
         String label = e.getValue().label();
         if (label == null || label.isBlank()) continue;
         BlockPos pos = BlockPos.of(e.getKey());
         // Skip orphan entries: the block at the recorded pos was destroyed
         // by an explosion / piston / world-edit and StorageRegistryHooks
         // didn't catch it. Returning a dead pos sends the villager on a
         // wasted walk. The chunk must be loaded for the check to be valid
         // — if it isn't, we can't tell, so keep the entry as a candidate
         // (better an occasional wasted walk than dropping all references
         // to unloaded storage).
         if (level.isLoaded(pos)
             && !com.yucareux.townfolk.town.StorageIndex.isStorage(level.getBlockEntity(pos))) {
            continue;
         }
         String l = label.toLowerCase(java.util.Locale.ROOT).trim();
         double d = near == null ? 0.0 : near.distSqr(pos);
         if (l.equals(needle)) {
            if (d < bestExact) { bestExact = d; exact = pos; }
         } else if (needle.length() >= MIN_LABEL_NEEDLE_CHARS && l.contains(needle)) {
            if (d < bestSub) { bestSub = d; substring = pos; }
         }
      }
      return exact != null ? exact : substring;
   }

   /** Update the "last touched" metadata on a registered container.
    *  Called from {@link com.yucareux.townfolk.world.ToolDispatcher}
    *  on deposit / withdraw / peek so the LLM-facing "Nearby storage"
    *  summary can show recency ("last touched by Anna, 3m ago"). */
   public static void touch(ServerLevel level, BlockPos pos, String who, long gameTime) {
      StorageRegistry reg = get(level);
      StorageConfig cfg = reg.byPos.get(pos.asLong());
      if (cfg == null) return;        // unregistered container — silently ignore
      cfg.touch(who, gameTime);
      // Bump mutation gen — touch() is called from every storage-modifying
      // verb (deposit/withdraw/peek), so this is the cleanest single hook
      // for invalidating the tag index. Misses player-direct chest edits
      // (no granular vanilla event), but those are rare and the meal-
      // window memo cache (21c) covers the staleness window.
      reg.mutationGen++;
      reg.setDirty();
   }

   /** Render the LLM-prompt-friendly summary of containers within
    *  {@code radius} XZ blocks of {@code centre}, capped at
    *  {@code maxEntries}. Replaces the deleted {@code TownStorageLedger.summaryNear}.
    *  Reads LIVE container contents (re-reads the BE each call) so
    *  the output never lies about what's actually in the barrels.
    *  Sorted by most-recently-touched first. */
   public static String summaryNear(ServerLevel level, BlockPos centre, int radius, int maxEntries) {
      StorageRegistry reg = get(level);
      if (reg.byPos.isEmpty()) return "";
      long r2 = (long) radius * radius;
      record Pick(BlockPos pos, StorageConfig cfg) {}
      java.util.List<Pick> picked = new java.util.ArrayList<>();
      for (var e : reg.byPos.entrySet()) {
         BlockPos p = BlockPos.of(e.getKey());
         long dx = p.getX() - centre.getX();
         long dz = p.getZ() - centre.getZ();
         if (dx * dx + dz * dz > r2) continue;
         picked.add(new Pick(p, e.getValue()));
      }
      if (picked.isEmpty()) return "";
      picked.sort((a, b) -> Long.compare(b.cfg.lastTouchedAt(), a.cfg.lastTouchedAt()));
      if (picked.size() > maxEntries) picked = picked.subList(0, maxEntries);

      long now = level.getGameTime();
      StringBuilder sb = new StringBuilder();
      for (Pick pk : picked) {
         String label = pk.cfg.label();
         sb.append("  - ");
         if (label != null && !label.isBlank()) {
            sb.append('"').append(label).append("\" (");
         }
         sb.append("container at ").append(pk.pos.toShortString());
         if (label != null && !label.isBlank()) sb.append(')');
         String toucher = pk.cfg.lastTouchedName();
         if (toucher != null && !toucher.isBlank()) {
            sb.append(" (last touched by ").append(toucher)
              .append(", ").append(ago(now - pk.cfg.lastTouchedAt())).append(" ago)");
         }
         sb.append(": ");
         var contents = liveContents(level, pk.pos);
         if (contents.isEmpty()) sb.append("empty");
         else {
            boolean first = true;
            for (var en : contents.entrySet()) {
               if (!first) sb.append(", ");
               sb.append(en.getValue()).append("× ").append(shortPath(en.getKey()));
               first = false;
            }
         }
         sb.append('\n');
      }
      return sb.toString();
   }

   /** One-shot snapshot of a registered container's live contents,
    *  keyed by item id → count. Empty stacks skipped, multi-slot
    *  same-item stacks summed. */
   private static java.util.Map<String, Integer> liveContents(ServerLevel level, BlockPos pos) {
      var be = level.getBlockEntity(pos);
      if (!(be instanceof net.minecraft.world.Container c)) return java.util.Map.of();
      java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
      for (int i = 0; i < c.getContainerSize(); i++) {
         var s = c.getItem(i);
         if (s.isEmpty()) continue;
         var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
         if (key == null) continue;
         out.merge(key.toString(), s.getCount(), Integer::sum);
      }
      return out;
   }

   private static String shortPath(String itemId) {
      int colon = itemId.indexOf(':');
      return colon < 0 ? itemId : itemId.substring(colon + 1);
   }

   private static String ago(long ticks) {
      if (ticks < 0) return "just now";
      if (ticks < 20L * 60) return (ticks / 20L) + "s";
      if (ticks < 24000L)   return (ticks / (20L * 60)) + "m";
      return (ticks / 24000L) + " day(s)";
   }

   // ───── tag reverse index (Stage 21a) ─────

   /** Return the set of registered container positions that hold at
    *  least one stack matching {@code tag} right now. Built lazily on
    *  first query per mutation generation; subsequent queries reuse
    *  the cached set until the next deposit/withdraw/peek/put/forget
    *  bumps {@link #mutationGen}.
    *
    *  <p>Returns an unmodifiable view — callers can iterate but must
    *  not mutate. Positions are returned as packed {@code asLong}s;
    *  unpack with {@link BlockPos#of(long)}. */
   public static Set<Long> tilesForTag(ServerLevel level, TagKey<Item> tag) {
      StorageRegistry reg = get(level);
      long gen = reg.mutationGen;
      IndexEntry cached = reg.tagIndex.get(tag);
      if (cached != null && cached.gen == gen) return cached.positions;

      // Rebuild: scan every registered container's live contents and
      // collect positions where any slot's item matches the tag.
      Set<Long> positions = new HashSet<>();
      for (var entry : reg.byPos.entrySet()) {
         BlockPos pos = BlockPos.of(entry.getKey());
         var container = com.yucareux.townfolk.town.ContainerAdapters.at(level, pos);
         if (container == null) continue;
         for (int i = 0; i < container.getContainerSize(); i++) {
            var stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(tag)) {
               positions.add(entry.getKey());
               break;     // one hit per container is enough
            }
         }
      }
      Set<Long> immut = Set.copyOf(positions);
      reg.tagIndex.put(tag, new IndexEntry(gen, immut));
      return immut;
   }

   /** Bump the mutation generation manually. Used by callers that
    *  modified a registered container without going through
    *  {@link #touch} (e.g. direct programmatic mutations during tests
    *  or future Create-mod fluid handlers that fill/drain without
    *  triggering a storage verb). */
   public static void bumpMutationGen(ServerLevel level) {
      StorageRegistry reg = get(level);
      reg.mutationGen++;
   }

   // ───── persistence ─────

   @Override
   public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
      ListTag list = new ListTag();
      for (var e : byPos.entrySet()) {
         CompoundTag ec = new CompoundTag();
         ec.putLong("pos", e.getKey());
         ec.put("config", e.getValue().save(registries));
         list.add(ec);
      }
      tag.put("entries", list);
      return tag;
   }

   private static StorageRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
      StorageRegistry reg = new StorageRegistry();
      if (!tag.contains("entries")) return reg;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         CompoundTag ec = list.getCompound(i);
         long pos = ec.getLong("pos");
         StorageConfig cfg = StorageConfig.load(ec.getCompound("config"), registries);
         reg.byPos.put(pos, cfg);
      }
      return reg;
   }
}
