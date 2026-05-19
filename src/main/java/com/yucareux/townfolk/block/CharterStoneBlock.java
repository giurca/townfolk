package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.dialogue.TownAdminService;
import com.yucareux.townfolk.registry.ModRegistries;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
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

   /** Stamp the Charter Stone's BE data onto the dropped ItemStack so a
    *  player who breaks the stone can place it back somewhere and have
    *  the same town pop right back up — same name, same treasury, same
    *  prestige, same villager roster, same log.
    *
    *  <p>{@code TownData.townSquarePos} isn't serialized — the BE's
    *  constructor sets it from the new world position on placement,
    *  and {@code town.load()} only writes back the actual data fields.
    *  So this works for both same-spot replace and full relocate.
    *
    *  <p>Vanilla's {@code BlockItem.updateCustomBlockEntityTag}
    *  handles the inverse direction (apply tag to freshly placed BE).
    *  No extra place-side wiring is needed. */
   @Override
   public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
      List<ItemStack> drops = super.getDrops(state, params);
      var be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
      if (!(be instanceof TownSquareBlockEntity town)) return drops;
      var registries = params.getLevel().registryAccess();
      var beTag = town.saveCustomOnly(registries);
      if (beTag.isEmpty()) return drops;
      var charterItem = ModRegistries.TOWN_SQUARE_ITEM.get();
      for (ItemStack s : drops) {
         if (s.is(charterItem)) {
            BlockItem.setBlockEntityData(s, town.getType(), beTag);
         }
      }
      return drops;
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
