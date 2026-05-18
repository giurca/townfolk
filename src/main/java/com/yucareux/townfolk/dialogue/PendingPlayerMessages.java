package com.yucareux.townfolk.dialogue;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.network.PlayerSpeaksPayload;
import com.yucareux.townfolk.network.VillagerReplyPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Buffers player chat lines aimed at a villager who is busy (mid-exchange,
 * mid-LLM-call, mid-summarizer). The message is delivered to the villager
 * the moment they become free, in FIFO order. The player gets a tiny
 * acknowledgment but their input is never lost.
 *
 * Limits:
 *   - max 3 pending lines per villager (oldest dropped if exceeded)
 *   - 60-second TTL per pending line (stale lines dropped)
 *
 * Replay path: on a 1-second tick, any villager that isn't busy AND has a
 * non-empty queue gets the oldest pending line dispatched back through
 * {@link DialogueService#handlePlayerSpeech}, exactly as if the player had
 * just typed it. Re-uses the existing pipeline — no new dialogue flow.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class PendingPlayerMessages {

   private static final int MAX_QUEUED_PER_VILLAGER = 3;
   private static final long TTL_TICKS = 20L * 60;       // 60 s
   private static final int POLL_TICKS = 20;             // 1 s

   private record Pending(UUID playerUuid, PlayerSpeaksPayload payload, long enqueuedTick) {}

   private static final Map<UUID, Deque<Pending>> QUEUES = new ConcurrentHashMap<>();

   public static void enqueue(UUID villagerUuid, UUID playerUuid, PlayerSpeaksPayload payload, long gameTime) {
      Deque<Pending> q = QUEUES.computeIfAbsent(villagerUuid, k -> new ArrayDeque<>());
      synchronized (q) {
         q.addLast(new Pending(playerUuid, payload, gameTime));
         while (q.size() > MAX_QUEUED_PER_VILLAGER) q.pollFirst();
      }
      VerboseLog.write("MSG_QUEUED",
         "villager=" + villagerUuid + " player=" + playerUuid + " depth=" + q.size(),
         payload.message());
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % POLL_TICKS != 0L) return;

      long now = level.getGameTime();
      for (var e : QUEUES.entrySet()) {
         UUID villagerUuid = e.getKey();
         Deque<Pending> q = e.getValue();
         Pending head;
         synchronized (q) { head = q.peekFirst(); }
         if (head == null) continue;
         // Drop stale.
         if (now - head.enqueuedTick() > TTL_TICKS) {
            synchronized (q) { q.pollFirst(); }
            VerboseLog.write("MSG_EXPIRED",
               "villager=" + villagerUuid + " player=" + head.playerUuid(),
               head.payload().message());
            continue;
         }
         // Wait until they're free.
         if (VillagerBusy.isBusy(villagerUuid)) continue;
         synchronized (q) { q.pollFirst(); }
         ServerPlayer player = level.getServer().getPlayerList().getPlayer(head.playerUuid());
         if (player == null) {
            VerboseLog.write("MSG_DROPPED",
               "villager=" + villagerUuid + " player=" + head.playerUuid() + " reason=player_offline", "");
            continue;
         }
         VerboseLog.write("MSG_FLUSH",
            "villager=" + villagerUuid + " player=" + player.getName().getString()
               + " queueDepth=" + q.size(), head.payload().message());
         DialogueService.handlePlayerSpeech(player, head.payload());
      }
   }

   private PendingPlayerMessages() {}
}
