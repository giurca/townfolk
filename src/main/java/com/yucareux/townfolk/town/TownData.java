package com.yucareux.townfolk.town;

import com.yucareux.townfolk.villager.PinnedFact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * In-memory model of a single town, owned by the Town Square block entity.
 * Holds editable town config, villager roster, and town-shared pinned facts
 * (history known to every resident: founding events, dragon attacks, etc).
 */
public final class TownData {

   public static final int MAX_TOWN_FACTS = 50;

   private String townName;
   private final BlockPos townSquarePos;
   private int defaultRadius;
   private final List<VillagerEntry> villagers;
   private final List<PinnedFact> townFacts;
   private final TownLog log = new TownLog();

   // Daily exchange caps — reset at day rollover.
   private final Map<String, Integer> exchangesByPair = new HashMap<>();
   private final Map<UUID, Integer> exchangesByVillager = new HashMap<>();
   private final Map<String, Long> lastExchangeTickByPair = new HashMap<>();
   private int exchangesTotalToday = 0;
   private long exchangeCounterDay = 0L;

   public TownData(BlockPos townSquarePos) {
      this.townName = "Unnamed Town";
      this.townSquarePos = townSquarePos;
      this.defaultRadius = 64;
      this.villagers = new ArrayList<>();
      this.townFacts = new ArrayList<>();
   }

   private static String pairKey(UUID a, UUID b) {
      return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
   }

   public void rolloverExchangeDayIfNeeded(long currentDay) {
      if (currentDay > this.exchangeCounterDay) {
         this.exchangesByPair.clear();
         this.exchangesByVillager.clear();
         this.exchangesTotalToday = 0;
         this.exchangeCounterDay = currentDay;
      }
   }
   public int exchangesTotalToday() { return this.exchangesTotalToday; }
   public int exchangesBetween(UUID a, UUID b) {
      return this.exchangesByPair.getOrDefault(pairKey(a, b), 0);
   }
   public int exchangesForVillager(UUID v) {
      return this.exchangesByVillager.getOrDefault(v, 0);
   }
   public void recordExchange(UUID a, UUID b, long gameTime) {
      this.exchangesByPair.merge(pairKey(a, b), 1, Integer::sum);
      this.exchangesByVillager.merge(a, 1, Integer::sum);
      this.exchangesByVillager.merge(b, 1, Integer::sum);
      this.exchangesTotalToday += 1;
      this.lastExchangeTickByPair.put(pairKey(a, b), gameTime);
   }
   public long lastExchangeTick(UUID a, UUID b) {
      return this.lastExchangeTickByPair.getOrDefault(pairKey(a, b), Long.MIN_VALUE / 2);
   }

   public String townName() { return this.townName; }
   public BlockPos townSquarePos() { return this.townSquarePos; }
   public int defaultRadius() { return this.defaultRadius; }
   public List<VillagerEntry> villagers() { return this.villagers; }
   public int villagerCount() { return this.villagers.size(); }
   public int aliveVillagerCount() { return (int) this.villagers.stream().filter(VillagerEntry::alive).count(); }
   public List<PinnedFact> townFacts() { return this.townFacts; }
   public TownLog log() { return this.log; }

   public void setTownName(String name) { this.townName = (name == null || name.isBlank()) ? "Unnamed Town" : name; }
   public void setDefaultRadius(int radius) { this.defaultRadius = Math.max(8, Math.min(2048, radius)); }

   public void addVillager(VillagerEntry entry) { this.villagers.add(entry); }

   public Optional<VillagerEntry> findVillager(UUID uuid) {
      for (VillagerEntry v : this.villagers) if (v.uuid().equals(uuid)) return Optional.of(v);
      return Optional.empty();
   }

   public boolean removeVillager(UUID uuid) {
      Iterator<VillagerEntry> it = this.villagers.iterator();
      while (it.hasNext()) {
         if (it.next().uuid().equals(uuid)) { it.remove(); return true; }
      }
      return false;
   }

   public void addTownFact(PinnedFact fact) {
      this.townFacts.add(fact);
      while (this.townFacts.size() > MAX_TOWN_FACTS) this.townFacts.remove(0);
   }

   public boolean removeTownFact(String id) {
      return this.townFacts.removeIf(f -> f.id().equals(id));
   }

   public Optional<PinnedFact> findTownFact(String id) {
      for (PinnedFact f : this.townFacts) if (f.id().equals(id)) return Optional.of(f);
      return Optional.empty();
   }

   public void replaceTownFact(PinnedFact updated) {
      for (int i = 0; i < this.townFacts.size(); i++) {
         if (this.townFacts.get(i).id().equals(updated.id())) {
            this.townFacts.set(i, updated);
            return;
         }
      }
   }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putString("townName", this.townName);
      tag.putInt("radius", this.defaultRadius);
      ListTag vlist = new ListTag();
      for (VillagerEntry v : this.villagers) vlist.add(v.save());
      tag.put("villagers", vlist);
      ListTag flist = new ListTag();
      for (PinnedFact f : this.townFacts) {
         CompoundTag fc = new CompoundTag();
         fc.putString("id", f.id());
         fc.putString("text", f.text());
         fc.putString("status", f.status());
         fc.putLong("createdDay", f.createdDay());
         fc.putBoolean("playerEdited", f.playerEdited());
         flist.add(fc);
      }
      tag.put("townFacts", flist);
      return tag;
   }

   public void load(CompoundTag tag) {
      this.townName = tag.contains("townName") ? tag.getString("townName") : "Unnamed Town";
      this.defaultRadius = tag.contains("radius") ? tag.getInt("radius") : 64;
      this.villagers.clear();
      if (tag.contains("villagers")) {
         ListTag list = tag.getList("villagers", Tag.TAG_COMPOUND);
         for (int i = 0; i < list.size(); i++) this.villagers.add(VillagerEntry.load(list.getCompound(i)));
      }
      this.townFacts.clear();
      if (tag.contains("townFacts")) {
         ListTag list = tag.getList("townFacts", Tag.TAG_COMPOUND);
         for (int i = 0; i < list.size(); i++) {
            CompoundTag fc = list.getCompound(i);
            this.townFacts.add(new PinnedFact(
               fc.getString("id"),
               fc.getString("text"),
               fc.contains("status") ? fc.getString("status") : "active",
               fc.contains("createdDay") ? fc.getLong("createdDay") : 0L,
               fc.contains("playerEdited") && fc.getBoolean("playerEdited")
            ));
         }
      }
   }
}
