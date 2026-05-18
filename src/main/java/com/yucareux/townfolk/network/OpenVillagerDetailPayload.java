package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: tells the (already-open or about-to-open) admin screen to
 * jump straight into the per-villager detail view for the given UUID.
 *
 * Used when the player sneak-right-clicks a villager — the server first
 * pushes the full {@link TownStateUpdatePayload} (which constructs the
 * screen), then immediately follows with this small payload to navigate
 * the screen to that villager. Decoupled from {@code TownStateUpdatePayload}
 * so the periodic refresh ticks don't accidentally yank focus back.
 */
public record OpenVillagerDetailPayload(long townSquarePos, UUID villagerUuid)
   implements CustomPacketPayload {

   public static final Type<OpenVillagerDetailPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "open_villager_detail"));

   public static final StreamCodec<RegistryFriendlyByteBuf, OpenVillagerDetailPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> { buf.writeLong(p.townSquarePos); buf.writeUUID(p.villagerUuid); },
         buf -> new OpenVillagerDetailPayload(buf.readLong(), buf.readUUID())
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
