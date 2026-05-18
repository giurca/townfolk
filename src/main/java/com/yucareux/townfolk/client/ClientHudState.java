package com.yucareux.townfolk.client;

import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;

/**
 * Client-side singleton cache of the last log snapshot received from the
 * server, plus the HUD-visibility flag. Read by the HUD overlay; written by
 * {@link ClientHooks} on every {@code TownLogPushPayload}.
 */
public final class ClientHudState {

   private static volatile long activeTownPos;
   private static volatile List<TownStateUpdatePayload.LogEntry> log = List.of();
   private static volatile boolean hudVisible;

   public static long activeTownPos() { return activeTownPos; }
   public static void setActiveTownPos(long pos) { activeTownPos = pos; }
   public static List<TownStateUpdatePayload.LogEntry> log() { return log; }
   public static void setLog(List<TownStateUpdatePayload.LogEntry> entries) { log = entries; }
   public static boolean isHudVisible() { return hudVisible; }
   public static void setHudVisible(boolean v) { hudVisible = v; }
   public static boolean toggleHud() { hudVisible = !hudVisible; return hudVisible; }

   private ClientHudState() {}
}
