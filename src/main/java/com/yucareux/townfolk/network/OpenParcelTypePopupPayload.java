package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the parcel-type selection popup. Sent after the
 * player captures corner B with the Surveyor's Stake and the parcel
 * passes volume/overlap validation. The two size hints are purely for
 * display in the popup ("8×12 parcel — what's it for?").
 */
public record OpenParcelTypePopupPayload(int sizeX, int sizeZ) implements CustomPacketPayload {

   public static final Type<OpenParcelTypePopupPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "open_parcel_type_popup"));

   public static final StreamCodec<RegistryFriendlyByteBuf, OpenParcelTypePopupPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> { buf.writeVarInt(p.sizeX); buf.writeVarInt(p.sizeZ); },
         buf -> new OpenParcelTypePopupPayload(buf.readVarInt(), buf.readVarInt()));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
