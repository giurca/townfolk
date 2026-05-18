package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * Wraps vanilla {@link net.minecraft.world.item.crafting.RecipeManager} so the
 * LLM's {@code [ACTION: craft <name>]} verb covers EVERY crafting recipe the
 * server knows about — bread, pumpkin pie, iron tools, you name it — instead
 * of a hand-curated table that has to be kept in sync.
 *
 * The catalog is built lazily on first use and cached per-server. We resolve
 * a user-supplied name (case-insensitive, accepts "wheat seeds" / "wheat_seeds"
 * / "minecraft:wheat_seeds" / just "seeds") to a registered {@link Item}, then
 * pick the first crafting recipe whose result matches. For items with multiple
 * recipes (e.g. wooden planks from different wood types), the first match
 * works — the LLM can specify an exact name if needed.
 */
public final class RecipeCatalog {

   /** Recipes that read like an item name but cost nothing in inventory — skip
    *  these so the LLM can't trivially "craft" raw materials. */
   private static final java.util.Set<String> EXCLUDED_RESULTS = java.util.Set.of(
      "minecraft:air"
   );

   private static volatile java.lang.ref.WeakReference<Level> CACHED_LEVEL_REF =
      new java.lang.ref.WeakReference<>(null);
   private static volatile Map<Item, RecipeHolder<?>> CACHE = Map.of();
   private static volatile Map<String, Item> NAME_TO_ITEM = Map.of();

   /** Look up a single crafting recipe by user-friendly output name.
    *  Returns empty if no item matches OR no crafting recipe produces it. */
   public static Optional<RecipeHolder<?>> findByOutputName(ServerLevel level, String rawName) {
      ensureCached(level);
      Item out = resolveItem(rawName);
      if (out == null) return Optional.empty();
      RecipeHolder<?> holder = CACHE.get(out);
      return Optional.ofNullable(holder);
   }

   public static Optional<Item> resolveItemOpt(String rawName) {
      ensureNameCache();
      Item it = resolveItem(rawName);
      return Optional.ofNullable(it);
   }

   private static Item resolveItem(String rawName) {
      if (rawName == null) return null;
      String n = rawName.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
      // Direct ID match first ("minecraft:bread" or "bread").
      ResourceLocation direct = ResourceLocation.tryParse(n.contains(":") ? n : "minecraft:" + n);
      if (direct != null) {
         Item it = BuiltInRegistries.ITEM.get(direct);
         // BuiltInRegistries returns AIR for unknown IDs; treat that as miss.
         if (it != null && BuiltInRegistries.ITEM.getKey(it).equals(direct)) return it;
      }
      // Fuzzy fallback: any item id whose path contains the query.
      Item best = NAME_TO_ITEM.get(n);
      if (best != null) return best;
      // Last-ditch substring scan.
      for (var entry : NAME_TO_ITEM.entrySet()) {
         if (entry.getKey().contains(n) || n.contains(entry.getKey())) return entry.getValue();
      }
      return null;
   }

   /** Convenience: an ingredient list with item-id + min count, for the LLM
    *  feedback paths. Each ingredient slot in the recipe contributes 1 to the
    *  matching item-id; identical items in different slots are summed. */
   public static LinkedHashMap<String, Integer> ingredientCounts(RecipeHolder<?> holder) {
      LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
      for (Ingredient ing : holder.value().getIngredients()) {
         if (ing.isEmpty()) continue;
         ItemStack[] items = ing.getItems();
         if (items.length == 0) continue;
         // Prefer the first item — recipes with alternatives (e.g. planks) are
         // satisfied by any, but for accounting we report the first concrete one.
         ResourceLocation id = BuiltInRegistries.ITEM.getKey(items[0].getItem());
         if (id == null) continue;
         out.merge(id.toString(), 1, Integer::sum);
      }
      return out;
   }

   /** Result of {@link #tryCraft}. {@code missing} is empty on success. */
   public record CraftAttempt(boolean ok, ItemStack produced, ItemStack leftover,
                              LinkedHashMap<String, Integer> missing) {}

   /** Inventory check + consumption. Returns a {@link CraftAttempt} describing
    *  what happened. On success, {@code produced} is the recipe output and any
    *  inventory overflow is in {@code leftover} (caller drops it at the actor). */
   public static CraftAttempt tryCraft(ServerLevel level, SimpleContainer inv, RecipeHolder<?> holder) {
      LinkedHashMap<String, Integer> need = ingredientCounts(holder);
      LinkedHashMap<String, Integer> missing = new LinkedHashMap<>();
      for (var entry : need.entrySet()) {
         int have = countItem(inv, entry.getKey());
         if (have < entry.getValue()) missing.put(entry.getKey(), entry.getValue() - have);
      }
      if (!missing.isEmpty()) {
         return new CraftAttempt(false, ItemStack.EMPTY, ItemStack.EMPTY, missing);
      }
      for (var entry : need.entrySet()) takeItem(inv, entry.getKey(), entry.getValue());

      ItemStack result;
      try {
         result = holder.value().getResultItem(level.registryAccess()).copy();
      } catch (Throwable t) {
         Townfolk.LOGGER.warn("Recipe result lookup failed for {}: {}", holder.id(), t.toString());
         result = ItemStack.EMPTY;
      }
      if (result.isEmpty()) {
         return new CraftAttempt(false, ItemStack.EMPTY, ItemStack.EMPTY, missing);
      }
      ItemStack leftover = inv.addItem(result.copy());
      return new CraftAttempt(true, result, leftover == null ? ItemStack.EMPTY : leftover,
         new LinkedHashMap<>());
   }

   // ───── private ─────

   private static void ensureCached(ServerLevel level) {
      Level cur = CACHED_LEVEL_REF.get();
      if (cur == level && !CACHE.isEmpty()) return;
      synchronized (RecipeCatalog.class) {
         if (CACHED_LEVEL_REF.get() == level && !CACHE.isEmpty()) return;
         Map<Item, RecipeHolder<?>> map = new LinkedHashMap<>();
         List<RecipeHolder<CraftingRecipe>> all = level.getRecipeManager()
            .getAllRecipesFor(RecipeType.CRAFTING);
         for (RecipeHolder<CraftingRecipe> h : all) {
            ItemStack result;
            try {
               result = h.value().getResultItem(level.registryAccess());
            } catch (Throwable t) {
               continue;
            }
            if (result.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(result.getItem());
            if (id == null) continue;
            if (EXCLUDED_RESULTS.contains(id.toString())) continue;
            // Keep the FIRST recipe per output. Stable iteration of
            // getAllRecipesFor means this is deterministic across reloads.
            map.putIfAbsent(result.getItem(), h);
         }
         CACHE = Map.copyOf(map);
         buildNameIndex();
         CACHED_LEVEL_REF = new java.lang.ref.WeakReference<>(level);
      }
   }

   private static void ensureNameCache() {
      if (!NAME_TO_ITEM.isEmpty()) return;
      synchronized (RecipeCatalog.class) {
         if (!NAME_TO_ITEM.isEmpty()) return;
         buildNameIndex();
      }
   }

   private static void buildNameIndex() {
      LinkedHashMap<String, Item> idx = new LinkedHashMap<>();
      for (var entry : BuiltInRegistries.ITEM.entrySet()) {
         ResourceLocation id = entry.getKey().location();
         idx.put(id.getPath(), entry.getValue());
      }
      NAME_TO_ITEM = Map.copyOf(idx);
   }

   private static int countItem(SimpleContainer inv, String itemId) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      int n = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() == target) n += s.getCount();
      }
      return n;
   }

   private static void takeItem(SimpleContainer inv, String itemId, int count) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      int need = count;
      for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() != target) continue;
         int take = Math.min(s.getCount(), need);
         s.shrink(take);
         need -= take;
         if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
      }
   }

   private RecipeCatalog() {}
}
