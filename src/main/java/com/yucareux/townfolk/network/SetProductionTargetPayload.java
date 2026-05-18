package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: set or remove a per-item production target for a
 * specific town.
 *
 * <p>Town-scoped: {@code townSquarePos} identifies which town this
 * policy belongs to. Without this scope the same item-cap policy
 * would apply to every town in the dimension, which is wrong as soon
 * as a player creates a second town.
 *
 * <p>When {@code enabled == true}, the server clamps {@code min ≤ max}
 * (both ≥ 0) and writes a target into
 * {@link com.yucareux.townfolk.town.ProductionTargets}. When
 * {@code enabled == false}, the existing target is removed and
 * production for that item becomes uncapped again. The {@code min /
 * max} fields are ignored on the disable path.
 *
 * <p>The {@code active} flag (live hysteresis state) is NOT part of
 * this payload — that's managed server-side based on stock crossing
 * the thresholds.
 */
public record SetProductionTargetPayload(long townSquarePos, String itemId,
                                          int min, int max, boolean enabled)
   implements CustomPacketPayload {

   public static final Type<SetProductionTargetPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "set_production_target"));

   public static final StreamCodec<RegistryFriendlyByteBuf, SetProductionTargetPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.townSquarePos);
            buf.writeUtf(p.itemId == null ? "" : p.itemId, 256);
            buf.writeVarInt(p.min);
            buf.writeVarInt(p.max);
            buf.writeBoolean(p.enabled);
         },
         buf -> new SetProductionTargetPayload(
            buf.readLong(), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

   /** Convenience: set bounds (enabled = true). */
   public static SetProductionTargetPayload setBounds(long townSquarePos, String itemId, int min, int max) {
      return new SetProductionTargetPayload(townSquarePos, itemId, min, max, true);
   }

   /** Convenience: remove the cap. */
   public static SetProductionTargetPayload disable(long townSquarePos, String itemId) {
      return new SetProductionTargetPayload(townSquarePos, itemId, 0, 0, false);
   }
}
