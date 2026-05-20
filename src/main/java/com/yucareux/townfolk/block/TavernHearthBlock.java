package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TavernHearthBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tavern Hearth — full-cube marker block for the
 * {@link com.yucareux.townfolk.building.BuildingTemplates#TAVERN}
 * template. A stone fireplace with glowing embers on top; emits
 * light, sits on the floor like furniture.
 *
 * <p>Replaces the original Tavern Sign (wall-mounted placard) so
 * placement is unambiguous: the hearth goes on a floor tile inside
 * the tavern. The recognizer's seed-finder picks the air block
 * above the hearth, which is always interior space when the hearth
 * is set up correctly — no more "tavern sign hung on the outside
 * wall confuses the flood fill" failure mode.
 *
 * <p>Mineable with stone+ pickaxe (data tags
 * {@code mineable/pickaxe} + {@code needs_stone_tool}).
 *
 * <p>No in-world interaction yet — no right-click menu for v1.
 * Future stages may add an "edit label" right-click flow or a
 * "what's the host doing tonight" peek.
 */
public class TavernHearthBlock extends BaseEntityBlock {

   public static final MapCodec<TavernHearthBlock> CODEC = simpleCodec(TavernHearthBlock::new);

   public TavernHearthBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   @Nullable
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new TavernHearthBlockEntity(pos, state);
   }
}
