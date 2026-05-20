package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.EntityTaskQueue;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: feed [species-hint]]} — find the closest animal
 * within 12 blocks (optional species hint), check the villager's
 * bag for something that animal will accept, queue an
 * {@link EntityTaskQueue} task that delivers one bite on arrival
 * (with HEART particles and an in-love trigger for adults).
 *
 * <p>Hint matches against the entity-type description ("Sheep",
 * "Cow") or the registered entity-id path ("sheep", "cow"), so the
 * LLM can write either form.
 *
 * <p>Extracted from {@code ToolDispatcher.doFeed} in stage 17a.5.
 */
public final class FeedVerb {

   private FeedVerb() {}

   public static void run(VerbContext ctx) {
      String hintKey = ctx.body().toLowerCase(Locale.ROOT).trim();
      var box = ctx.actor().getBoundingBox().inflate(12);
      Animal target = null;
      ItemStack matchingFood = null;
      double bestDist = Double.MAX_VALUE;

      var inv = ctx.actor().getInventory();
      List<ItemStack> bag = new ArrayList<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (!s.isEmpty()) bag.add(s);
      }
      if (bag.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "feed — nothing in my bag to offer");
         return;
      }

      for (var a : ctx.level().getEntitiesOfClass(Animal.class, box,
            an -> an.isAlive() && (hintKey.isEmpty()
               || an.getType().getDescription().getString().toLowerCase(Locale.ROOT).contains(hintKey)
               || (BuiltInRegistries.ENTITY_TYPE.getKey(an.getType()) != null
                  && BuiltInRegistries.ENTITY_TYPE.getKey(an.getType()).getPath().contains(hintKey))))) {
         for (var stack : bag) {
            if (a.isFood(stack)) {
               double d = ctx.actor().distanceToSqr(a);
               if (d < bestDist) { bestDist = d; target = a; matchingFood = stack; }
               break;
            }
         }
      }
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            hintKey.isEmpty()
               ? "feed — no animal near me will accept anything I'm carrying"
               : "feed — no " + hintKey + " near me that will accept anything I'm carrying");
         return;
      }
      final Animal animal = target;
      final ItemStack foodFinal = matchingFood;
      EntityTaskQueue.enqueue(ctx.level(), ctx.actor(), new EntityTaskQueue.EntityTask(
         ctx.actor().getUUID(), animal.getUUID(),
         ctx.level().getGameTime() + 20L * 30, "feed",
         (lvl, v, t) -> {
            if (!(t instanceof Animal an)) {
               throw new RuntimeException("target is no longer an animal");
            }
            var inv2 = v.getInventory();
            int slot = -1;
            for (int i = 0; i < inv2.getContainerSize(); i++) {
               if (!inv2.getItem(i).isEmpty()
                   && inv2.getItem(i).getItem() == foodFinal.getItem()
                   && an.isFood(inv2.getItem(i))) {
                  slot = i; break;
               }
            }
            if (slot < 0) throw new RuntimeException("food gone before delivery");
            var bite = inv2.getItem(slot).copy();
            bite.setCount(1);
            inv2.getItem(slot).shrink(1);
            if (inv2.getItem(slot).isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
            if (!an.isBaby() && an.canFallInLove()) an.setInLove(null);
            lvl.sendParticles(ParticleTypes.HEART,
               an.getX(), an.getY() + an.getBbHeight(), an.getZ(),
               4, 0.2, 0.2, 0.2, 0);
            long day = lvl.getGameTime() / 24000L;
            String foodName = bite.getHoverName().getString();
            String animalName = an.getType().getDescription().getString();
            MemoryStore.write(v, "work", day,
               "I fed a " + animalName + " a piece of " + foodName + ".");
            return "fed a " + animalName + " 1× " + foodName;
         }));
   }
}
