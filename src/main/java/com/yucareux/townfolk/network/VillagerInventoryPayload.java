package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Server → client: snapshot of a villager's inventory in response to a
 * {@link RequestVillagerInventoryPayload}. Sent only to the requester.
 *
 * Includes the held mainhand/offhand and the 8 internal SimpleContainer
 * slots so the panel can show the full picture — tools the villager is
 * currently using, plus the bag of stuff they're hauling.
 */
public record VillagerInventoryPayload(UUID villager, List<ItemStack> slots)
      implements CustomPacketPayload {

   public static final Type<VillagerInventoryPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "villager_inventory"));

   public static final StreamCodec<RegistryFriendlyByteBuf, VillagerInventoryPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUUID(p.villager);
            buf.writeVarInt(p.slots.size());
            for (ItemStack s : p.slots) {
               ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
            }
         },
         buf -> {
            UUID u = buf.readUUID();
            int n = buf.readVarInt();
            List<ItemStack> slots = new ArrayList<>(n);
            for (int i = 0; i < n; i++) slots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            return new VillagerInventoryPayload(u, slots);
         });

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
