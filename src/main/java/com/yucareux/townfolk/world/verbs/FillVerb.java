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

      // The held stack must expose IFluidHandlerItem AND contain
      // something to pour. Audit P1 fix: walk main → off → bag and
      // prefer the first NON-EMPTY fluid container. Without the
      // non-empty preference, a leading empty bucket in slot 0 would
      // win and the verb would fail "my container is empty" even
      // though the villager has a full bucket later in the bag.
      ItemStack sourceStack = null;
      IFluidHandlerItem source = null;
      var probe = FluidIndex.onStack(ctx.actor().getMainHandItem());
      if (probe != null && !FluidIndex.contents(probe).isEmpty()) {
         sourceStack = ctx.actor().getMainHandItem();
         source = probe;
      }
      if (source == null) {
         probe = FluidIndex.onStack(ctx.actor().getOffhandItem());
         if (probe != null && !FluidIndex.contents(probe).isEmpty()) {
            sourceStack = ctx.actor().getOffhandItem();
            source = probe;
         }
      }
      if (source == null) {
         var inv = ctx.actor().getInventory();
         for (int i = 0; i < inv.getContainerSize() && source == null; i++) {
            ItemStack s = inv.getItem(i);
            IFluidHandlerItem probeBag = FluidIndex.onStack(s);
            if (probeBag != null && !FluidIndex.contents(probeBag).isEmpty()) {
               source = probeBag;
               sourceStack = s;
            }
         }
      }
      if (source == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — no non-empty fluid container in my bag");
         return;
      }
      FluidStack inSource = FluidIndex.contents(source);
      // Audit P1 fix: validate fluidHint against the source's actual
      // fluid. If the LLM says "fill water" but we're carrying lava,
      // refuse rather than silently pumping lava into the irrigation
      // tank. Substring match against the localized name covers
      // modded "Flowing Water" / "Whole Milk" naming.
      if (!fluidHint.isEmpty()
          && !inSource.getHoverName().getString().toLowerCase(Locale.ROOT).contains(fluidHint)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — my container holds " + inSource.getHoverName().getString()
               + ", not " + fluidHint);
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

      // Audit P1 fix: vanilla BucketItem's FluidBucketWrapper is
      // all-or-nothing on drain — requesting 800mB returns EMPTY,
      // not a partial. Without care the tank gains 800mB while the
      // bucket stays full = duplication. The correct dance:
      //
      //   1. Simulate the tank fill at our intended amount → learn
      //      how much it would accept (`tankWillAccept`).
      //   2. Simulate a source drain at that amount → learn what
      //      the source ACTUALLY allows pulling (`sourceWillGive`).
      //      For a bucket this snaps to 0 or 1000.
      //   3. Take the min of the two; EXECUTE drain on the source
      //      first, then EXECUTE fill on the tank.
      //
      // If sourceWillGive == 0 (e.g. bucket can't partial-drain to
      // fit a half-full tank), refuse the action so neither side
      // moves. No duplication path remains.
      FluidStack draftRequest = inSource.copy();
      draftRequest.setAmount(Math.min(amount, inSource.getAmount()));
      int tankWillAccept = target.fill(draftRequest.copy(), IFluidHandler.FluidAction.SIMULATE);
      if (tankWillAccept <= 0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — tank rejected the fluid (wrong type, or full)");
         return;
      }
      FluidStack drainSimSpec = inSource.copy();
      drainSimSpec.setAmount(tankWillAccept);
      FluidStack sourceWillGive = source.drain(drainSimSpec, IFluidHandler.FluidAction.SIMULATE);
      if (sourceWillGive.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "fill — my bucket can't pour a partial amount (tank only had room for "
               + tankWillAccept + "mB)");
         return;
      }
      int moveAmount = Math.min(tankWillAccept, sourceWillGive.getAmount());
      FluidStack drainExec = inSource.copy();
      drainExec.setAmount(moveAmount);
      FluidStack drained = source.drain(drainExec, IFluidHandler.FluidAction.EXECUTE);
      int filled = target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
      // Sanity: if filled < drained.getAmount() something off-spec
      // happened (race?) — return the unfilled remainder to the source.
      if (filled < drained.getAmount()) {
         FluidStack refund = drained.copy();
         refund.setAmount(drained.getAmount() - filled);
         source.fill(refund, IFluidHandler.FluidAction.EXECUTE);
      }

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
