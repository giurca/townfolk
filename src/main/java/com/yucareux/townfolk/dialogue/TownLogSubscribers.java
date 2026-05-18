package com.yucareux.townfolk.dialogue;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.network.LogSubscribePayload;
import com.yucareux.townfolk.network.TownLogPushPayload;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side tracker: which players want a live log stream for which town.
 * Pushes the log to subscribers ~1×/sec, regardless of whether the admin UI
 * is open. Enables the in-game HUD overlay.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TownLogSubscribers {

   private static final ConcurrentHashMap<UUID, Long> SUBSCRIBERS = new ConcurrentHashMap<>();
   private static final int PUSH_TICKS = 20;

   public static void handle(Player rawPlayer, LogSubscribePayload payload) {
      if (!(rawPlayer instanceof ServerPlayer player)) return;
      if (payload.enable()) {
         SUBSCRIBERS.put(player.getUUID(), payload.townSquarePos());
      } else {
         SUBSCRIBERS.remove(player.getUUID());
      }
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % PUSH_TICKS != 0L) return;
      if (SUBSCRIBERS.isEmpty()) return;

      for (var e : SUBSCRIBERS.entrySet()) {
         ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.getKey());
         if (player == null) continue;
         if (player.serverLevel() != level) continue;
         BlockPos pos = BlockPos.of(e.getValue());
         BlockEntity be = level.getBlockEntity(pos);
         if (!(be instanceof TownSquareBlockEntity town)) continue;
         var snap = town.getTown().log().snapshot();
         List<TownStateUpdatePayload.LogEntry> wire = new ArrayList<>(snap.size());
         for (var entry : snap) {
            wire.add(new TownStateUpdatePayload.LogEntry(entry.gameTime(), entry.level().name(), entry.message()));
         }
         PacketDistributor.sendToPlayer(player, new TownLogPushPayload(e.getValue(), wire));
      }
   }

   private TownLogSubscribers() {}
}
