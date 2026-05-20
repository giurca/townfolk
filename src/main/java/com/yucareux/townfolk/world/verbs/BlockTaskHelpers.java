package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.BlockTaskQueue;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared helpers for block-task verbs (harvest, plant, chop, mine,
 * place, till, water, livestock-action follow-up). Extracted from
 * {@code ToolDispatcher} in stage 17a.1.
 *
 * <p>Pairs with {@link StorageHelpers} — that one owns
 * inventory/container concerns, this one owns world-block search,
 * mining, and BlockTaskQueue dispatch.
 */
public final class BlockTaskHelpers {

   /** Hard ceiling on how long a single block-task waits for the
    *  villager to arrive before timing out. 30s = generous for most
    *  builds; the queue cancels on phase changes anyway. */
   public static final int BLOCK_TASK_TIMEOUT_TICKS = 20 * 30;

   private BlockTaskHelpers() {}

   /** Find the closest block in {@code radius} (XZ cube, ±3 Y) matching
    *  {@code pred}. Returns null if none. Flat AABB scan — fine for
    *  radius ≤ 12. */
   public static BlockPos findClosest(ServerLevel level, BlockPos centre, int radius,
                                       Predicate<BlockState> pred) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (!pred.test(level.getBlockState(cur))) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) { bestDist = d; best = cur.immutable(); }
            }
         }
      }
      return best;
   }

   /** Like {@link #findClosest} but predicate takes a {@link BlockPos}
    *  so it can consult neighbouring blocks. Same scan range. */
   public static BlockPos findClosestPos(ServerLevel level, BlockPos centre, int radius,
                                          Predicate<BlockPos> pred) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (!pred.test(cur)) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) { bestDist = d; best = cur.immutable(); }
            }
         }
      }
      return best;
   }

   /** Break a block, write its drops into {@code actor}'s inventory
    *  (overflow drops at the actor), and return a concise summary
    *  string. If the broken block sat on farmland AND the villager has
    *  a seed in inventory, auto-replant — keeps the harvest loop
    *  continuous instead of leaving fallow rows. */
   public static String breakAndCollect(ServerLevel level, Villager actor, BlockPos pos, String verb) {
      var state = level.getBlockState(pos);
      boolean wasOnFarmland = level.getBlockState(pos.below()).is(Blocks.FARMLAND);
      var drops = Block.getDrops(state, level, pos, null, actor, actor.getMainHandItem());
      level.destroyBlock(pos, false, actor);
      int totalCount = 0;
      String firstName = null;
      var inv = actor.getInventory();
      for (var drop : drops) {
         if (drop.isEmpty()) continue;
         if (firstName == null)
            firstName = StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(drop.getItem()).toString());
         totalCount += drop.getCount();
         ItemStack leftover = inv.addItem(drop);
         if (leftover != null && !leftover.isEmpty()) {
            var entity = new ItemEntity(level, actor.getX(), actor.getY(), actor.getZ(), leftover);
            entity.setPickUpDelay(20);
            level.addFreshEntity(entity);
         }
      }
      if (firstName == null) firstName = state.getBlock().getDescriptionId();

      String replantNote = "";
      if (wasOnFarmland && level.getBlockState(pos).isAir()) {
         var inv2 = actor.getInventory();
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            var s = inv2.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof CropBlock) {
               level.setBlockAndUpdate(pos, bi.getBlock().defaultBlockState());
               s.shrink(1);
               if (s.isEmpty()) inv2.setItem(i, ItemStack.EMPTY);
               replantNote = " + replanted "
                  + StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(bi.asItem()).toString());
               break;
            }
         }
      }
      return verb + " " + totalCount + "× " + firstName + " from " + pos.toShortString() + replantNote;
   }

   /** True if {@code actor} carries any item with the given tag — used
    *  by axe/pickaxe/hoe-gated verbs. Matches via the runtime
    *  {@link ItemStack#is(TagKey)} check, so datapack-added tools work. */
   public static boolean hasToolWithTag(Villager actor, TagKey<Item> tag) {
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (s.isEmpty()) continue;
         if (s.is(tag)) return true;
      }
      return false;
   }

   /** Common dispatch path for block-task verbs — queues an arrival
    *  callback against {@code target} with the standard 30s timeout. */
   public static void enqueueBlockTask(ServerLevel level, Villager actor, BlockPos target,
                                        String verb, BlockTaskQueue.OnArrive onArrive) {
      BlockTaskQueue.enqueue(level, actor, new BlockTaskQueue.BlockTask(
         actor.getUUID(), target,
         level.getGameTime() + BLOCK_TASK_TIMEOUT_TICKS,
         verb, onArrive));
   }
}
