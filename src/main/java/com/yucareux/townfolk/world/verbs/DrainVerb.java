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
 * {@code [ACTION: drain [<amount>mB] [<fluid>] [from <tank-label>]]}
 * — pull fluid OUT of a registered tank INTO the villager's held
 * bucket-like item.
 *
 * <p>Inverse of {@link FillVerb}. Same capability-driven design.
 * Stage 23 / 20b.
 */
public final class DrainVerb {

   private static final Pattern DRAIN_PAT = Pattern.compile(
      "(?:(\\d+)\\s*(?:mB|mb|ml)?\\s+)?(?:([\\w' ]+?)\\s+)?(?:from\\s+(.+))?",
      Pattern.CASE_INSENSITIVE);

   private DrainVerb() {}

   public static void run(VerbContext ctx) {
      Matcher m = DRAIN_PAT.matcher(ctx.body().trim());
      if (!m.matches()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — malformed");
         return;
      }
      int amount = m.group(1) == null ? Integer.MAX_VALUE
         : Math.max(1, Math.min(64_000, Integer.parseInt(m.group(1))));
      String fluidHint = m.group(2) == null ? "" : m.group(2).trim().toLowerCase(Locale.ROOT);
      String labelHint = m.group(3) == null ? "" : m.group(3).trim();

      // Held container needs IFluidHandlerItem (will accept the drain).
      ItemStack heldStack = ctx.actor().getMainHandItem();
      IFluidHandlerItem dest = FluidIndex.onStack(heldStack);
      if (dest == null) {
         heldStack = ctx.actor().getOffhandItem();
         dest = FluidIndex.onStack(heldStack);
      }
      if (dest == null) {
         var inv = ctx.actor().getInventory();
         for (int i = 0; i < inv.getContainerSize() && dest == null; i++) {
            dest = FluidIndex.onStack(inv.getItem(i));
         }
      }
      if (dest == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — no fluid container in my bag to receive the drain");
         return;
      }

      BlockPos targetPos = FillVerb.resolveTargetTank(ctx, labelHint);
      if (targetPos == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — no tank found"
               + (labelHint.isEmpty() ? " within reach" : " named \"" + labelHint + "\""));
         return;
      }
      IFluidHandler source = FluidIndex.at(ctx.level(), targetPos);
      if (source == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — block at " + targetPos.toShortString() + " has no fluid output");
         return;
      }
      // Simulate a drain to discover what fluid we'd pull. If the
      // caller hinted a specific fluid, narrow to first matching tank.
      FluidStack drafted = source.drain(amount, IFluidHandler.FluidAction.SIMULATE);
      if (drafted.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — tank is empty");
         return;
      }
      if (!fluidHint.isEmpty()
          && !drafted.getHoverName().getString().toLowerCase(Locale.ROOT).contains(fluidHint)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — the tank holds " + drafted.getHoverName().getString()
               + ", not " + fluidHint);
         return;
      }
      // Try to fill the held container — limited to what it can hold.
      int filled = dest.fill(drafted, IFluidHandler.FluidAction.EXECUTE);
      if (filled <= 0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "drain — my container can't accept that fluid");
         return;
      }
      // Now actually pull from the tank.
      FluidStack realDrain = drafted.copy();
      realDrain.setAmount(filled);
      source.drain(realDrain, IFluidHandler.FluidAction.EXECUTE);

      StorageRegistry.touch(ctx.level(), targetPos, ctx.self().name(), ctx.level().getGameTime());
      String fluidName = drafted.getHoverName().getString();
      String msg = ctx.self().name() + " drained " + filled + "mB " + fluidName
         + " from the tank at " + targetPos.toShortString();
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=drained",
         "drained=" + filled + " fluid=" + fluidName + " pos=" + targetPos.toShortString());
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "drained " + filled + "mB " + fluidName
            + " from " + StorageHelpers.containerRefFor(ctx.level(), targetPos));
   }
}
