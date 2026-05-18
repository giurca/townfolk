package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "I'm looking at this block and pressing the Assign hotkey;
 * assign it as a home/workstation to whichever nearby villager has a matching
 * open need." Server figures out which villager + which slot. No villager UUID
 * required in the packet — the server decides based on proximity + open needs.
 */
public record AssignBlockPayload(long blockPos) implements CustomPacketPayload {

   public static final Type<AssignBlockPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "assign_block"));

   public static final StreamCodec<RegistryFriendlyByteBuf, AssignBlockPayload> STREAM_CODEC =
      StreamCodec.of((buf, p) -> buf.writeLong(p.blockPos), buf -> new AssignBlockPayload(buf.readLong()));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

   public BlockPos pos() { return BlockPos.of(this.blockPos); }
}
