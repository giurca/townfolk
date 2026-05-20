package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.FluidIndex;
import com.yucareux.townfolk.town.StorageRegistry;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * {@code [ACTION: fill <amount>mB <fluid> [in <tank-label>]]} —
 * transfer fluid FROM the villager's held bucket-like item INTO a
 * registered tank within reach.
 *
 * <p>Stage 23 / 20b. Decoupled from Create — works against any
 * NeoForge {@link IFluidHandler} capability provider (Create tanks,
 * vanilla cauldron's fluid-handler-via-capability if any mod adds
 * one, Mekanism, etc.).
 *
 * <p>Body grammar accepts either form:
 * <ul>
 *   <li>{@code fill water} — fill the held bucket-stack into the
 *       nearest registered tank, taking whatever amount fits.
 *   <li>{@code fill 500 water in irrigation_tank} — explicit amount
 *       + target label.
 * </ul>
 */
public final class FillVerb {

   /** {@code fill [N[mB]] <fluid> [in/into <label>]}. */
   private static final Pattern FILL_PAT = Pattern.compile(
      "(?:(\\d+)\\s*(?:mB|mb|ml)?\\s+)?([\\w' ]+?)(?:\\s+(?:in|into)\\s+(.+))?",
      Pattern.CASE_INSENSITIVE);

   private FillVerb() {}

   public static void run(VerbContext ctx) {
      Matcher m = FILL_PAT.matcher(ctx.body());
      if (!m.matches()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — malformed (expected \"fill [amount] <fluid> [in <tank>]\")");
         return;
      }
      int amount = m.group(1) == null ? Integer.MAX_VALUE
         : Math.max(1, Math.min(64_000, Integer.parseInt(m.group(1))));
      String fluidHint = m.group(2).trim().toLowerCase(Locale.ROOT);
      String labelHint = m.group(3) == null ? "" : m.group(3).trim();

      // The held stack must expose IFluidHandlerItem. Check main hand,
      // then off hand, then bag.
      ItemStack sourceStack = ctx.actor().getMainHandItem();
      IFluidHandlerItem source = FluidIndex.onStack(sourceStack);
      if (source == null) {
         sourceStack = ctx.actor().getOffhandItem();
         source = FluidIndex.onStack(sourceStack);
      }
      if (source == null) {
         var inv = ctx.actor().getInventory();
         for (int i = 0; i < inv.getContainerSize() && source == null; i++) {
            ItemStack s = inv.getItem(i);
            source = FluidIndex.onStack(s);
            if (source != null) sourceStack = s;
         }
      }
      if (source == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — no fluid container in my bag (need a bucket-like item)");
         return;
      }
      FluidStack inSource = FluidIndex.contents(source);
      if (inSource.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — my container is empty");
         return;
      }

      // Resolve the target tank — by label if hinted, else nearest
      // registered position with a fluid handler.
      BlockPos targetPos = resolveTargetTank(ctx, labelHint);
      if (targetPos == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — no tank found"
               + (labelHint.isEmpty() ? " within reach" : " named \"" + labelHint + "\""));
         return;
      }
      IFluidHandler target = FluidIndex.at(ctx.level(), targetPos);
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — block at " + targetPos.toShortString() + " has no fluid input");
         return;
      }

      // Build the transfer attempt.
      FluidStack draftMax = inSource.copy();
      draftMax.setAmount(Math.min(amount, inSource.getAmount()));
      int filled = target.fill(draftMax, IFluidHandler.FluidAction.EXECUTE);
      if (filled <= 0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — tank rejected the fluid (wrong type, or full)");
         return;
      }
      // Decrement the source container by the actual filled amount.
      FluidStack drainSpec = inSource.copy();
      drainSpec.setAmount(filled);
      source.drain(drainSpec, IFluidHandler.FluidAction.EXECUTE);

      StorageRegistry.touch(ctx.level(), targetPos, ctx.self().name(), ctx.level().getGameTime());
      String fluidName = inSource.getHoverName().getString();
      String msg = ctx.self().name() + " filled " + filled + "mB " + fluidName
         + " into the tank at " + targetPos.toShortString();
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=filled",
         "filled=" + filled + " fluid=" + fluidName + " pos=" + targetPos.toShortString());
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "filled " + filled + "mB " + fluidName
            + " into " + StorageHelpers.containerRefFor(ctx.level(), targetPos));
   }

   /** Pick a tank by label if hinted, otherwise the nearest registered
    *  container that exposes a fluid-handler capability. */
   static BlockPos resolveTargetTank(VerbContext ctx, String labelHint) {
      if (!labelHint.isEmpty()) {
         BlockPos lp = StorageRegistry.findByLabel(ctx.level(), labelHint, ctx.actor().blockPosition());
         if (lp != null && FluidIndex.at(ctx.level(), lp) != null) return lp;
         return null;
      }
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (var entry : StorageRegistry.entries(ctx.level())) {
         BlockPos p = BlockPos.of(entry.getKey());
         double d = p.distSqr(ctx.actor().blockPosition());
         if (d > 6 * 6) continue;        // out of reach
         if (d >= bestDist) continue;
         if (FluidIndex.at(ctx.level(), p) == null) continue;
         best = p;
         bestDist = d;
      }
      return best;
   }
}
