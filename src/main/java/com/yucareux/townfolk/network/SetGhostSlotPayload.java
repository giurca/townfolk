package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Client → server: update a single ghost filter slot in the currently
 * open {@link com.yucareux.townfolk.world.inventory.StorageConfigMenu}.
 *
 * Used by recipe-viewer compat plugins (JEI / EMI). When the player
 * drag-drops an item from JEI's sidebar onto a filter slot, the plugin
 * fires this payload — the server stores a single-count copy in the
 * referenced filter slot, then vanilla's normal {@code broadcastChanges}
 * pipeline echoes the slot back to the client UI.
 */
public record SetGhostSlotPayload(int slotIndex, ItemStack stack) implements CustomPacketPayload {

   public static final Type<SetGhostSlotPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "set_ghost_slot"));

   public static final StreamCodec<RegistryFriendlyByteBuf, SetGhostSlotPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeVarInt(p.slotIndex);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.stack);
         },
         buf -> new SetGhostSlotPayload(
            buf.readVarInt(),
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
