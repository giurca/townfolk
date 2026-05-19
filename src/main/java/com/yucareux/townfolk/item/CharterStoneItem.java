package com.yucareux.townfolk.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

/**
 * The BlockItem form of a Charter Stone. Exists for one reason: read
 * the {@link DataComponents#BLOCK_ENTITY_DATA} component on the stack
 * (set by {@link com.yucareux.townfolk.block.CharterStoneBlock}'s
 * drop-side handling) and surface a tooltip summary so the player
 * knows the stack is carrying a real town's saved state — not just a
 * generic blank stone.
 *
 * <p>Vanilla's {@code BlockItem.updateCustomBlockEntityTag} handles
 * the inverse direction (apply the tag back to the freshly placed BE
 * on right-click) for us — no override needed there.
 */
public class CharterStoneItem extends BlockItem {

   public CharterStoneItem(Block block, Properties props) {
      super(block, props);
   }

   @Override
   public void appendHoverText(ItemStack stack, TooltipContext ctx,
                                List<Component> lines, TooltipFlag flag) {
      super.appendHoverText(stack, ctx, lines, flag);
      CustomData beData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
      if (beData == null) return;
      CompoundTag tag = beData.copyTag();
      if (!tag.contains("town")) return;
      CompoundTag town = tag.getCompound("town");

      // Best-effort summary fields — all optional, defaulting cleanly.
      String name = town.contains("townName") ? town.getString("townName") : "Unnamed Town";
      int prestige = town.contains("prestige") ? town.getInt("prestige") : 0;
      int treasuryItems = 0;
      if (town.contains("treasury")) {
         CompoundTag treas = town.getCompound("treasury");
         for (String key : treas.getAllKeys()) treasuryItems += treas.getInt(key);
      }
      int villagers = 0;
      if (town.contains("villagers")) {
         villagers = town.getList("villagers", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
      }

      lines.add(Component.literal("Town: " + name)
         .withStyle(ChatFormatting.GOLD));
      lines.add(Component.literal("  Prestige: " + prestige
            + "   Villagers: " + villagers
            + "   Treasury: " + treasuryItems + " items")
         .withStyle(ChatFormatting.GRAY));
      lines.add(Component.literal("Re-place to restore town state.")
         .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
   }
}
