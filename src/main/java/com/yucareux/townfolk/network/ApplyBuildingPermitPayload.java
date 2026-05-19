package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player clicked Apply in the Building Permit
 * modal. Server takes one Building Permit from the player's bag,
 * writes the new override to the recognized building at
 * {@code markerPos}, and re-runs validation.
 *
 * <p>Either field set to -1 means "use template default" (i.e. clear
 * any prior override). 0 or positive = an explicit cap value;
 * server clamps to the legal range.
 *
 * @param markerPos    packed BlockPos of the marker. Must match a
 *                     known {@code RecognizedBuilding}.
 * @param maxVolume    requested volume override, or -1 for default
 * @param maxHeight    requested height override, or -1 for default
 */
public record ApplyBuildingPermitPayload(long markerPos, int maxVolume, int maxHeight)
   implements CustomPacketPayload {

   public static final Type<ApplyBuildingPermitPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "apply_building_permit"));

   public static final StreamCodec<RegistryFriendlyByteBuf, ApplyBuildingPermitPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.markerPos);
            buf.writeVarInt(p.maxVolume + 1);
            buf.writeVarInt(p.maxHeight + 1);
         },
         buf -> new ApplyBuildingPermitPayload(
            buf.readLong(),
            buf.readVarInt() - 1,
            buf.readVarInt() - 1)
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
