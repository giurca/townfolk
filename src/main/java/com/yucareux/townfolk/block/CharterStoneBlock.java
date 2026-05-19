package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.dialogue.TownAdminService;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Charter Stone — anchor block for a town. Right-clicking opens the
 * town admin UI.
 *
 * <p>Renamed from "Town Square" in Stage 10a. The block-registry ID
 * stays as {@code townfolk:town_square} for save compatibility —
 * worlds created before the rename keep loading. The class name,
 * display name, and visual model all use the new naming.
 *
 * <p>Doubles as the marker block for the Town Hall building (see
 * {@link com.yucareux.townfolk.building.BuildingTemplates#TOWN_HALL}).
 * Place a Charter Stone, enclose it in a small room with a door and
 * a banner, and the town hall is recognized — granting +4 to the
 * population cap.
 */
public class CharterStoneBlock extends BaseEntityBlock {

   public static final MapCodec<CharterStoneBlock> CODEC = simpleCodec(CharterStoneBlock::new);

   public CharterStoneBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   @Nullable
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new TownSquareBlockEntity(pos, state);
   }

   @Override
   protected RenderShape getRenderShape(BlockState state) {
      // Custom monolith model — needs MODEL render type to draw the
      // non-cube geometry.
      return RenderShape.MODEL;
   }

   @Override
   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
      if (level.isClientSide) {
         return InteractionResult.SUCCESS;
      }
      if (!(player instanceof ServerPlayer server)) {
         return InteractionResult.PASS;
      }
      BlockEntity be = level.getBlockEntity(pos);
      if (be instanceof TownSquareBlockEntity town) {
         TownAdminService.openAdminPanel(server, (ServerLevel) level, town);
      }
      return InteractionResult.CONSUME;
   }
}
