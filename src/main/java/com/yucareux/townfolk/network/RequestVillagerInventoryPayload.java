package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "the player is hovering this villager — send me a fresh
 * snapshot of their inventory so I can render the hover panel". The client
 * rate-limits these (one per UUID-change + a slow refresh ticker) so the
 * server doesn't get flooded.
 */
public record RequestVillagerInventoryPayload(UUID villager) implements CustomPacketPayload {

   public static final Type<RequestVillagerInventoryPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "req_villager_inventory"));

   public static final StreamCodec<RegistryFriendlyByteBuf, RequestVillagerInventoryPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> buf.writeUUID(p.villager),
         buf -> new RequestVillagerInventoryPayload(buf.readUUID()));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
