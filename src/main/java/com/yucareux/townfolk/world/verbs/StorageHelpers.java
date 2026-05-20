package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.town.StorageRegistry;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Storage- and item-resolution helpers shared by every verb that
 * touches inventory or container contents. Extracted from
 * {@code ToolDispatcher} in stage 17a.1.
 *
 * <p>Held as one utility class instead of split per-concern because
 * every storage verb pulls from 3–5 of these (regex + item-resolve
 * + take/count/add + sound/log). Splitting further would mean each
 * verb file imports 4 utility classes.
 */
public final class StorageHelpers {

   /** {@code give Alice: 3 wheat} — recipient name, optional count, item. */
   public static final Pattern GIVE = Pattern.compile(
      "([\\w' ]+?)\\s*:\\s*(\\d+)?\\s*(.+)", Pattern.CASE_INSENSITIVE);

   /** Body like {@code "5 wheat"} or {@code "wheat"} (default count 1)
    *  or {@code "all wheat"}. Used by deposit / withdraw. The trailing
    *  in/from/to alternation is intentionally NOT in the pattern — the
    *  caller strips that label-hint suffix first to avoid non-greedy
    *  backtracking stealing item-name tails ("potato" → "pota"). */
   public static final Pattern QTY_ITEM = Pattern.compile(
      "(?:(\\d+|all)\\s+)?([\\w' ]+?)\\s*$", Pattern.CASE_INSENSITIVE);

   /** {@code hand [N] <item> to <player>} */
   public static final Pattern HAND_PATTERN = Pattern.compile(
      "(?:(\\d+|all)\\s+)?(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);

   private static final ConcurrentHashMap<String, Item> ITEM_TOKEN_CACHE = new ConcurrentHashMap<>();
   private static final Item ITEM_MISS_SENTINEL = Items.AIR;

   private StorageHelpers() {}

   /** Resolve an LLM-provided item token (may or may not have a
    *  namespace) to an actual {@link Item}. Falls back to a registry
    *  scan when the namespaced lookup hits AIR — necessary because the
    *  LLM frequently writes unprefixed modded paths (e.g.
    *  {@code "cabbage_seeds"} instead of {@code "farmersdelight:cabbage_seeds"}).
    *
    *  <p>Caches hits AND misses against the lowercased token so repeat
    *  lookups are O(1). Cache is bounded only by the set of distinct
    *  tokens the LLM has ever emitted; small in practice.
    *
    *  @return null if the token genuinely matches no item */
   public static Item resolveItemFlexibly(String itemTok) {
      if (itemTok == null || itemTok.isBlank()) return null;
      String tok = itemTok.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
      Item cached = ITEM_TOKEN_CACHE.get(tok);
      if (cached != null) {
         return cached == ITEM_MISS_SENTINEL && !"air".equals(tok) ? null : cached;
      }
      ResourceLocation id = ResourceLocation.tryParse(tok.contains(":") ? tok : "minecraft:" + tok);
      if (id != null) {
         Item item = BuiltInRegistries.ITEM.get(id);
         if (item != null && (item != Items.AIR || "air".equals(tok))) {
            ITEM_TOKEN_CACHE.put(tok, item);
            return item;
         }
      }
      String bare = tok.contains(":") ? tok.substring(tok.indexOf(':') + 1) : tok;
      for (var entry : BuiltInRegistries.ITEM.entrySet()) {
         if (entry.getKey().location().getPath().equals(bare)) {
            ITEM_TOKEN_CACHE.put(tok, entry.getValue());
            return entry.getValue();
         }
      }
      ITEM_TOKEN_CACHE.put(tok, ITEM_MISS_SENTINEL);
      return null;
   }

   /** Subtract up to {@code count} of {@code itemId} from {@code inv},
    *  draining the smallest slots first. Mutates the container. */
   public static void takeItem(SimpleContainer inv, String itemId, int count) {
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

   /** Total count of {@code itemId} across all slots of {@code inv}. */
   public static int countItem(SimpleContainer inv, String itemId) {
      ResourceLocation id = ResourceLocation.parse(itemId);
      Item target = BuiltInRegistries.ITEM.get(id);
      int n = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() == target) n += s.getCount();
      }
      return n;
   }

   /** Insert {@code stack} into {@code container} — merging into
    *  existing matching stacks first, then placing into empty slots.
    *  Returns any leftover that didn't fit. */
   public static ItemStack addToContainer(Container container, ItemStack stack) {
      ItemStack rem = stack.copy();
      for (int i = 0; i < container.getContainerSize() && !rem.isEmpty(); i++) {
         ItemStack s = container.getItem(i);
         if (s.isEmpty()) continue;
         if (!ItemStack.isSameItemSameComponents(s, rem)) continue;
         int max = Math.min(s.getMaxStackSize(), container.getMaxStackSize());
         int free = max - s.getCount();
         if (free <= 0) continue;
         int put = Math.min(free, rem.getCount());
         s.grow(put);
         rem.shrink(put);
      }
      for (int i = 0; i < container.getContainerSize() && !rem.isEmpty(); i++) {
         ItemStack s = container.getItem(i);
         if (!s.isEmpty()) continue;
         ItemStack copy = rem.copy();
         int max = Math.min(rem.getMaxStackSize(), container.getMaxStackSize());
         copy.setCount(Math.min(max, rem.getCount()));
         container.setItem(i, copy);
         rem.shrink(copy.getCount());
      }
      return rem;
   }

   /** Strip the {@code namespace:} prefix and turn underscores into
    *  spaces. Used in admin logs + memory store summaries. */
   public static String shortName(String itemId) {
      int colon = itemId.indexOf(':');
      return (colon < 0 ? itemId : itemId.substring(colon + 1)).replace('_', ' ');
   }

   /** True if {@code actor} carries any quantity of {@code itemId}. */
   public static boolean hasItem(Villager actor, String itemId) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == target) return true;
      }
      return false;
   }

   /** Play the open / close click for the appropriate container block
    *  (barrel, shulker, ender chest, or generic chest). Centralised so
    *  every verb that opens a container sounds the same. */
   public static void playContainerSound(ServerLevel level, BlockPos pos, boolean opening) {
      var block = level.getBlockState(pos).getBlock();
      SoundEvent sound;
      if (block == Blocks.BARREL) {
         sound = opening ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
      } else if (block instanceof ShulkerBoxBlock) {
         sound = opening ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE;
      } else if (block == Blocks.ENDER_CHEST) {
         sound = opening ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE;
      } else {
         sound = opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
      }
      level.playSound(null, pos, sound, SoundSource.BLOCKS,
         0.5f, level.getRandom().nextFloat() * 0.1f + 0.9f);
   }

   /** Render a barrel reference using the player-set label if any:
    *  {@code "the 'wool stash' barrel at 880405, -864, -5876093"} or
    *  fallback to {@code "the container at <pos>"}. Used in memories +
    *  ActionFeedback so the LLM hears the same name the player sees
    *  in the StorageConfig UI and admin Resources tab. */
   public static String containerRefFor(ServerLevel level, BlockPos pos) {
      var cfg = StorageRegistry.find(level, pos);
      String label = cfg == null ? "" : cfg.label();
      if (label != null && !label.isBlank()) {
         return "the \"" + label + "\" container at " + pos.toShortString();
      }
      return "the container at " + pos.toShortString();
   }
}
