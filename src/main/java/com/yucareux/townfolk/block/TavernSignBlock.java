package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TavernSignBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tavern Sign — marker block for the
 * {@link com.yucareux.townfolk.building.BuildingTemplates#TAVERN}
 * template. Place one inside a room that satisfies the template
 * (door + 20 m² floor + 1× barrel + 1× brewing stand) and the
 * building recognizer registers an active tavern there.
 *
 * <p>Block + BE only; no in-world interaction (no right-click menu
 * for v1). Future stages may add an "edit label" right-click flow.
 *
 * <p>Mineable with axe (see {@code data/minecraft/tags/block/mineable/axe.json}).
 * No tool tier required — wood-equivalent.
 */
public class TavernSignBlock extends BaseEntityBlock {

   public static final MapCodec<TavernSignBlock> CODEC = simpleCodec(TavernSignBlock::new);

   public TavernSignBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   @Nullable
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new TavernSignBlockEntity(pos, state);
   }

   @Override
   protected RenderShape getRenderShape(BlockState state) {
      // Custom model — non-cube placard shape. MODEL render path is
      // required for the non-cube geometry to draw correctly.
      return RenderShape.MODEL;
   }
}
