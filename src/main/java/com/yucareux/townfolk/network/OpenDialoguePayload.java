package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the dialogue screen, scoped to the given villager.
 *
 * Carries display name, backstory, recent dialogue history, AND a list of
 * the villager's open need-tagged todos (so the dialogue screen can render
 * a needs banner with one-click action buttons).
 */
public record OpenDialoguePayload(UUID villagerUuid, String villagerName,
                                  String backstory, List<Turn> history,
                                  List<NeedFlag> needs)
   implements CustomPacketPayload {

   public static final Type<OpenDialoguePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "open_dialogue"));

   public record Turn(String role, String text) {}
   public record NeedFlag(String kind, String label) {}   // kind = "home"|"job", label = display text

   public static final StreamCodec<RegistryFriendlyByteBuf, OpenDialoguePayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            // Raw UUID (16 bytes) — see PlayerSpeaksPayload for rationale.
            buf.writeUUID(p.villagerUuid);
            buf.writeUtf(p.villagerName);
            buf.writeUtf(p.backstory, 4096);
            buf.writeVarInt(p.history.size());
            for (Turn t : p.history) { buf.writeUtf(t.role()); buf.writeUtf(t.text(), 1024); }
            buf.writeVarInt(p.needs.size());
            for (NeedFlag n : p.needs) { buf.writeUtf(n.kind()); buf.writeUtf(n.label(), 256); }
         },
         buf -> {
            UUID uuid = buf.readUUID();
            String name = buf.readUtf();
            String story = buf.readUtf(4096);
            int h = buf.readVarInt();
            List<Turn> history = new ArrayList<>(h);
            for (int i = 0; i < h; i++) history.add(new Turn(buf.readUtf(), buf.readUtf(1024)));
            int n = buf.readVarInt();
            List<NeedFlag> needs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) needs.add(new NeedFlag(buf.readUtf(), buf.readUtf(256)));
            return new OpenDialoguePayload(uuid, name, story, history, needs);
         }
      );

   @Override
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
