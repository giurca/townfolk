package com.yucareux.townfolk.entity;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets a villager pass through closed wooden doors and fence gates.
 * Vanilla villagers get this from their Brain's {@code InteractWithDoor}
 * task — but {@link LlmTownsfolk} neuters the Brain, so we re-add the
 * bare mechanical version here as a Goal: open ahead, close behind.
 *
 * Pairs with {@link TownsfolkNodeEvaluator} which classifies closed
 * fence gates as {@code DOOR_WOOD_CLOSED} (passable in pathfinding)
 * instead of vanilla's {@code FENCE} (impassable). Without that
 * evaluator override, the pathfinder routes AROUND closed gates and
 * this Goal never sees them as upcoming path nodes; with it, the path
 * naturally includes the gate and the lookahead below opens it.
 *
 * Close-behind is the important half — without it, a villager who
 * walks into a sheep pen opens the gate and lets the sheep out. With
 * it, the gate auto-closes once the villager is ~3 blocks past,
 * keeping the pen sealed.
 *
 * Iron doors are intentionally NOT touched — they need redstone and
 * represent a deliberate "no entry". Trapdoors are skipped too.
 */
public class DoorInteractionGoal extends Goal {

   private final Mob mob;

   /** Blocks this goal opened that haven't yet been closed behind us. Position
    *  is the LOWER half of a door or the single block of a fence gate. */
   private final Set<BlockPos> openedByMe = new HashSet<>();

   /** Game-time tick at which we opened each entry in {@link #openedByMe}.
    *  Close-behind enforces a minimum dwell time so we don't open and
    *  immediately close a door that was opened from path-lookahead while
    *  the villager was still > CLOSE_BEHIND_DIST away. Without this, a
    *  door 3 blocks ahead on the path opens in one tick and closes in
    *  the same tick — looking from the outside like a 10 Hz flicker. */
   private final java.util.Map<BlockPos, Long> openedAtTick = new java.util.HashMap<>();

   /** Minimum ticks a door / gate must be open before close-behind can
    *  fire on it. 10 ticks = 0.5 s — plenty for the villager to take
    *  the next step or two toward the door, but short enough that the
    *  door isn't left hanging open for long when the villager really
    *  is past it. */
   private static final long MIN_OPEN_TICKS = 10L;

   /** For each fence gate we opened, the world-space point the villager
    *  MUST walk past before close-behind fires. Computed at open-time
    *  as "2.5 blocks past the gate in the direction of travel". If the
    *  villager's current task target lands within the gate threshold
    *  (e.g. sheep parked right inside the pen), the close-behind would
    *  otherwise never trigger and the gate would stay open while they
    *  shear, letting livestock escape. The forced push gets them
    *  through, gate closes, then they walk back to the actual target. */
   private final java.util.Map<BlockPos, net.minecraft.world.phys.Vec3> mustWalkPast
      = new java.util.HashMap<>();

   /** Look this far ahead on the path for DOORS. 2 nodes ≈ one tick of
    *  margin at villager walking speed before reaching the obstacle. */
   private static final int LOOKAHEAD_NODES = 2;

   /** Look this far ahead for FENCE GATES specifically. Tighter than
    *  the door lookahead because every extra block of gate-open-time
    *  is another tick during which a nearby sheep / pig / cow can
    *  slip through the open gate. 0 = only open when the villager
    *  is standing on the gate node. The pathfinder briefly stalls
    *  one tick to perform the open, but that's invisible vs. the
    *  alternative of livestock escaping the pen.
    *
    *  <p>Note: this only works because {@code TownsfolkNodeEvaluator}
    *  reclassifies closed gates as passable. Without that, the path
    *  wouldn't include the gate node and we'd never open it. */
   private static final int GATE_LOOKAHEAD_NODES = 0;

   /** Squared distance past a door / gate at which we close it behind us.
    *  ~1.5 blocks — tight enough that a sheep pen reseals before any
    *  sheep can squeeze through, loose enough that the gate doesn't
    *  snap shut while the villager is still passing the doorway. */
   private static final double CLOSE_BEHIND_DIST_SQ = 2.25;

   /** How many blocks past a fence gate we force the villager to walk
    *  before close-behind is allowed. User-spec: "walk forward 2 blocks
    *  min so the gate closes properly". */
   private static final double WALK_PAST_GATE_BLOCKS = 2.5;

   public DoorInteractionGoal(Mob mob) {
      this.mob = mob;
      // No movement / look flags — we run alongside other goals.
      setFlags(EnumSet.noneOf(Flag.class));
   }

   @Override
   public boolean canUse() {
      // Run only when there's something to look at: a navigating
      // villager (might open something) or already-opened blocks
      // waiting to be closed behind. Idle villagers stay idle.
      return mob.getNavigation().isInProgress() || !openedByMe.isEmpty();
   }

   @Override
   public boolean canContinueToUse() { return canUse(); }

   @Override
   public boolean requiresUpdateEveryTick() { return false; }

   @Override
   public void tick() {
      Level level = mob.level();

      // Open doors / gates on the upcoming path nodes. The custom node
      // evaluator means closed fence gates ARE on the path now, so the
      // pathfinder routes through them and we see them in this loop.
      if (mob.getNavigation().isInProgress()) {
         var path = mob.getNavigation().getPath();
         if (path != null) {
            int from = path.getNextNodeIndex();
            int to = Math.min(path.getNodeCount(), from + LOOKAHEAD_NODES + 1);
            for (int i = from; i < to; i++) {
               // Steps-ahead index: 0 = current node, 1 = next, etc.
               int stepsAhead = i - from;
               BlockPos pos = path.getNode(i).asBlockPos();
               openIfClosed(level, pos, stepsAhead);
               // Doors are two blocks tall; the path may target the lower
               // half — check the upper half too just in case.
               openIfClosed(level, pos.above(), stepsAhead);
            }
         }
      }

      // FORCE WALK-PAST: for each gate we've opened that still has a
      // must-walk-past target, push the villager further IF they've
      // stopped (or are about to stop) within 2 blocks of the gate.
      // Sheep pens are the canonical case — a sheep parked right inside
      // the gate would otherwise have the villager shear from arm's-
      // reach of the doorway, leaving the gate hanging open. Forcing a
      // 2.5-block penetration lets close-behind fire normally.
      var pushIt = mustWalkPast.entrySet().iterator();
      while (pushIt.hasNext()) {
         var e = pushIt.next();
         BlockPos gate = e.getKey();
         var target = e.getValue();
         if (!openedByMe.contains(gate)) {
            // Gate already closed (probably manually); abandon push.
            pushIt.remove();
            continue;
         }
         double distFromGate2 = mob.distanceToSqr(gate.getX() + 0.5, mob.getY(), gate.getZ() + 0.5);
         if (distFromGate2 >= CLOSE_BEHIND_DIST_SQ) {
            // Far enough — close-behind below will do its job. Drop push.
            pushIt.remove();
            continue;
         }
         // Still too close to the gate. If nav is idle, push them to the
         // walk-past point so close-behind can fire.
         if (!mob.getNavigation().isInProgress()) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, 0.55);
            com.yucareux.townfolk.diag.VerboseLog.write("GATE_FORCE_PUSH",
               "actor=" + nameOf() + " gate=" + gate.toShortString()
                  + " target=(" + String.format("%.1f", target.x) + ","
                  + String.format("%.1f", target.y) + ","
                  + String.format("%.1f", target.z) + ")"
                  + " distSq=" + String.format("%.2f", distFromGate2), "");
         }
      }

      // Close anything we opened once we're well past it. Enforces a
      // minimum dwell time (MIN_OPEN_TICKS) so a door opened from
      // path-lookahead while the villager was still > CLOSE_BEHIND_DIST
      // away doesn't flicker open-and-close in the same tick.
      long now = level.getGameTime();
      Iterator<BlockPos> it = openedByMe.iterator();
      while (it.hasNext()) {
         BlockPos pos = it.next();
         // MIN_OPEN_TICKS only applies to doors. Gates use lookahead=0,
         // so the villager is ON the gate node at open time — the
         // flicker the dwell timer was designed to prevent can't
         // happen here, and every extra tick the gate is open is
         // another tick a sheep can slip through.
         boolean isGate = level.getBlockState(pos).getBlock() instanceof FenceGateBlock;
         Long openedAt = openedAtTick.get(pos);
         if (!isGate && openedAt != null && now - openedAt < MIN_OPEN_TICKS) continue;
         double dx = mob.getX() - (pos.getX() + 0.5);
         double dy = mob.getY() - (pos.getY() + 0.5);
         double dz = mob.getZ() - (pos.getZ() + 0.5);
         if (dx * dx + dy * dy + dz * dz > CLOSE_BEHIND_DIST_SQ) {
            closeIfOpen(level, pos);
            it.remove();
            openedAtTick.remove(pos);
            mustWalkPast.remove(pos);     // gate closed, drop any push entry
         }
      }
   }

   private void openIfClosed(Level level, BlockPos pos, int stepsAhead) {
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof DoorBlock door) {
         // Skip iron doors — they need redstone and represent intentional locking.
         if (state.is(net.minecraft.world.level.block.Blocks.IRON_DOOR)) return;
         if (!state.getValue(DoorBlock.OPEN)) {
            door.setOpen(mob, level, state, pos, true);
            // Record the lower half as the anchor for close-behind.
            BlockPos anchor = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
               ? pos.below() : pos;
            BlockPos immutable = anchor.immutable();
            openedByMe.add(immutable);
            openedAtTick.put(immutable, level.getGameTime());
            com.yucareux.townfolk.diag.VerboseLog.write("DOOR_OPEN",
               "actor=" + nameOf() + " pos=" + anchor.toShortString(), "");
         }
      } else if (state.getBlock() instanceof FenceGateBlock gate) {
         // Tighter lookahead for gates: don't open them until the villager
         // is literally about to step onto the gate node. Every extra
         // block of "open early" is another window during which a sheep
         // near the gate can slip through.
         if (stepsAhead > GATE_LOOKAHEAD_NODES) return;
         if (!state.getValue(FenceGateBlock.OPEN)) {
            // Before opening, gently displace any livestock loitering on
            // the immediate threshold so they don't dart through during
            // the brief open window.
            nudgeNearbyAnimalsAwayFromGate(level, pos);
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
            level.playSound(null, pos,
               SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            BlockPos gatePos = pos.immutable();
            openedByMe.add(gatePos);
            openedAtTick.put(gatePos, level.getGameTime());
            mustWalkPast.put(gatePos, computeWalkPastTarget(gatePos));
            com.yucareux.townfolk.diag.VerboseLog.write("GATE_OPEN",
               "actor=" + nameOf() + " pos=" + gatePos.toShortString()
                  + " walkPast=" + mustWalkPast.get(gatePos), "");
         }
      }
   }

   /** Push any passive livestock (cow, sheep, pig, chicken, etc.) that
    *  are within ~1.5 blocks of the gate position away from the gate.
    *  Animals' AI is curious — a villager opening a gate next to a
    *  parked sheep is a near-guaranteed escape if the sheep is just
    *  standing on the threshold. A small velocity nudge moves them
    *  half a block back into the pen, well past the gate's reach
    *  during the brief open window. */
   private void nudgeNearbyAnimalsAwayFromGate(Level level, BlockPos gate) {
      var box = new net.minecraft.world.phys.AABB(gate).inflate(1.5);
      var animals = level.getEntitiesOfClass(
         net.minecraft.world.entity.animal.Animal.class, box,
         a -> a != mob && a.isAlive());
      if (animals.isEmpty()) return;
      net.minecraft.world.phys.Vec3 gateCentre =
         net.minecraft.world.phys.Vec3.atCenterOf(gate);
      for (var a : animals) {
         net.minecraft.world.phys.Vec3 away = a.position().subtract(gateCentre);
         double len = away.length();
         if (len < 1.0e-3) continue;
         net.minecraft.world.phys.Vec3 push = away.scale(0.35 / len);
         a.setDeltaMovement(a.getDeltaMovement().add(push.x, 0.0, push.z));
         a.hurtMarked = true;
      }
      com.yucareux.townfolk.diag.VerboseLog.write("GATE_NUDGE",
         "actor=" + nameOf() + " gate=" + gate.toShortString()
            + " animals=" + animals.size(), "");
   }

   /** Compute a world-space point ~{@link #WALK_PAST_GATE_BLOCKS} past
    *  the gate in the direction of current travel. Uses the path's next
    *  post-gate node when available, falling back to the villager→gate
    *  vector extended past the gate. */
   private net.minecraft.world.phys.Vec3 computeWalkPastTarget(BlockPos gate) {
      net.minecraft.world.phys.Vec3 gateCentre =
         net.minecraft.world.phys.Vec3.atCenterOf(gate);
      var path = mob.getNavigation().getPath();
      if (path != null) {
         // Find the gate node, then take the node after it.
         for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            if (path.getNode(i).asBlockPos().equals(gate)) {
               int post = Math.min(i + 1, path.getNodeCount() - 1);
               if (post > i) {
                  var postNode = net.minecraft.world.phys.Vec3.atCenterOf(
                     path.getNode(post).asBlockPos());
                  net.minecraft.world.phys.Vec3 dir = postNode.subtract(gateCentre).normalize();
                  return gateCentre.add(dir.scale(WALK_PAST_GATE_BLOCKS));
               }
            }
         }
      }
      // Fallback: project past the gate along the villager→gate vector.
      net.minecraft.world.phys.Vec3 here = mob.position();
      net.minecraft.world.phys.Vec3 dir = gateCentre.subtract(here);
      double len = dir.length();
      if (len < 1.0e-3) return gateCentre;
      return gateCentre.add(dir.scale(WALK_PAST_GATE_BLOCKS / len));
   }

   private void closeIfOpen(Level level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof DoorBlock door) {
         if (state.getValue(DoorBlock.OPEN)) {
            door.setOpen(mob, level, state, pos, false);
            com.yucareux.townfolk.diag.VerboseLog.write("DOOR_CLOSE",
               "actor=" + nameOf() + " pos=" + pos.toShortString(), "");
         }
      } else if (state.getBlock() instanceof FenceGateBlock gate) {
         if (state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), 10);
            level.playSound(null, pos,
               SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
            com.yucareux.townfolk.diag.VerboseLog.write("GATE_CLOSE",
               "actor=" + nameOf() + " pos=" + pos.toShortString(), "");
         }
      }
   }

   private String nameOf() {
      return mob.hasCustomName() ? mob.getCustomName().getString()
                                 : mob.getUUID().toString().substring(0, 8);
   }
}
