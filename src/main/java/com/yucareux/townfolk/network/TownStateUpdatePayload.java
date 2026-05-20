package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: full snapshot of a town's admin state.
 *
 * One big payload by design — at 50 villagers the wire cost is still small
 * and the simple "everything in one push" model dodges a class of stale-view
 * bugs that a per-tab/incremental scheme would introduce. We can split later
 * if profiling demands it.
 *
 * Each villager summary carries identity (name/role/persona/backstory) +
 * mind (beliefs/pins/todos/memoryCount) + body (pos/health/activity/inventory
 * /anchors). The town carries name, log, OpenRouter
 * stats, and the storage ledger snapshot.
 *
 * <h2>Aggregate fields (Resources tab)</h2>
 * {@link #aggregateResources} is the per-item-id total across every
 * registered container. {@link #resourceLocations} is the inverse index:
 * for any item id, the list of containers holding it + how many. Both are
 * cheap to compute (one walk of the registered-storage entries we already
 * push in {@link #storage}) and let the UI render the resources tab
 * without a follow-up query.
 *
 * <h2>Overview counters</h2>
 * {@link #populationAlive}, {@link #populationTotal}, {@link #idleCount},
 * {@link #workingCount}, {@link #sleepingCount}, {@link #birthsToday},
 * {@link #deathsToday}. All filled by the server side; idle/working are
 * derived from the per-villager {@code activity} string.
 */
public record TownStateUpdatePayload(
   long townSquarePos,
   String townName,
   String openrouterStatus,
   double openrouterUsage,
   double openrouterLimit,
   double totalTownCostUsd,
   List<VillagerSummary> villagers,
   List<PinSummary> townFacts,
   List<LogEntry> log,
   List<StorageEntry> storage,
   // ── new fields for the 50+ town admin UI ──
   List<ItemCount> aggregateResources,
   List<ResourceLoc> resourceLocations,
   List<ProductionTarget> productionTargets,
   List<ParcelSummary> parcels,
   List<TradeOfferView> tradeOffers,
   /** Withdrawable items in the town's "magical" treasury pool —
    *  accumulated trade payouts that aren't tied to any physical
    *  container. Each ItemCount uses trend=0. */
   List<ItemCount> treasury,
   int populationAlive,
   int populationTotal,
   /** Number of registered TRADE_POST auxiliary blocks. Gates the Trade tab. */
   int tradePostCount,
   /** Town prestige scalar [0, TownData.MAX_PRESTIGE]. Used by the
    *  Trade tab v1 placeholder and (Stage 3) by tier gating. */
   int prestige,
   /** Active Home buildings in the level (Stage 10a). */
   int homeCount,
   /** True iff the town has a recognized Town Hall building (Charter
    *  Stone enclosed with door + banner). Adds 4 to the population
    *  cap when true. */
   boolean hasTownHall,
   /** Active Tavern buildings (Stage 11a). Unlocks the TAVERN leisure
    *  option for villagers in coverage. No mechanical effect on the
    *  population cap or other stats — flavor + LLM context only. */
   int tavernCount,
   int idleCount,
   int workingCount,
   int sleepingCount,
   int birthsToday,
   int deathsToday
) implements CustomPacketPayload {

   public static final Type<TownStateUpdatePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "town_state_update"));

   public record LogEntry(long gameTime, String level, String message) {}
   public record PinSummary(String id, String text, String status, long createdDay) {}
   public record TodoSummary(String id, String text, String counterparty, String status, long createdDay) {}
   /** Item-id + count tuple used in three places:
    *    - aggregateResources (town stockpile, ships trend = -1/0/+1)
    *    - per-villager inventories (trend always 0)
    *    - per-container contents (trend always 0)
    *  Only the aggregate-resources callsite sets trend; everything
    *  else passes 0 because the trend signal is only meaningful at
    *  town-aggregate granularity. */
   public record ItemCount(String itemId, int count, int trend) {
      /** Trend-less convenience for callsites that don't have / need
       *  day-over-day data. */
      public ItemCount(String itemId, int count) { this(itemId, count, 0); }
   }
   public record StorageEntry(long packedPos, String blockKind, String lastUpdaterName,
                              long lastUpdatedTick, List<ItemCount> contents,
                              String label) {}

   /** One row in the per-item drill-down: "this item is in this container,
    *  this many of it." Sorted server-side by descending count so the UI
    *  doesn't have to. */
   public record ResourceLoc(String itemId, long packedPos, int count) {}

   /** Per-item production policy: stockpile range with hysteresis.
    *  {@link #active} reflects the live state — true when villagers
    *  are currently producing, false when the cap is reached and
    *  they're waiting to drop below {@link #min}. */
   public record ProductionTarget(String itemId, int min, int max, boolean active) {}

   /** Client-side view of one active trade offer. The full
    *  {@link com.yucareux.townfolk.trade.TradeOffer} lives on the
    *  server; here we ship only what the UI needs to render and let
    *  the client fulfil. {@code daysRemaining} is precomputed by the
    *  server so the client doesn't need its own day clock. */
   public record TradeOfferView(
      String id,
      String archetypeName,         // e.g. "Wandering Wizard"
      String tierName,              // "COMMON" / "NOTABLE" / "PREMIUM"
      String requestItemId,
      int requestCount,
      int paymentEmeralds,
      int daysRemaining,
      String flavorBlurb
   ) {}

   /** Per-parcel snapshot. {@link #ownerUuid} ties back to the
    *  {@link VillagerSummary#uuid()} list — UI joins them when rendering
    *  "Beatrix's animal parcel @ 880405, -864, -5876097 — 8×5, 1 day old". */
   public record ParcelSummary(
      String id,
      UUID ownerUuid,
      String ownerName,
      String type,           // "PLANT" / "ANIMAL"
      long centerPos,
      int sizeX,
      int sizeZ,
      long createdDay,
      // Optional content snapshot — what's on this parcel right now:
      int ripeCrops, int emptyFarmland, int tillableTiles,
      int animalCount, int animalBabies, int unshornSheep
   ) {}

   public record VillagerSummary(
      UUID uuid,
      String name,
      String role,
      String personaSeed,
      String backstory,
      boolean alive,
      int llmCalls,
      long inputTokens,
      long outputTokens,
      double estCostUsd,
      String beliefs,
      List<PinSummary> pinnedFacts,
      int memoryCount,
      long lastCompactedDay,
      List<TodoSummary> todos,
      long packedPos,
      float health,
      float maxHealth,
      String activity,
      boolean playerSetHome,
      boolean playerSetJob,
      List<ItemCount> inventory,
      // ── new for Villagers-tab filter/sort ──
      String profession,        // "none" / "farmer" / "shepherd" / "butcher" / "mason"
      // ── Stage 12a: hunger 0..100, 100 = full, 0 = starving ──
      int hunger,
      // ── Stage 18a: identity stripe ──
      // gender ∈ {"male","female"}; ageDays starts at 200 (adult) for
      // pre-18a saves, ticks +1 per game day at dawn via ScheduleService.
      String gender,
      int ageDays
   ) {}

   private static final int MAX_LOG_MESSAGE_CHARS = 4096;

   private static final StreamCodec<RegistryFriendlyByteBuf, LogEntry> LOG_CODEC =
      StreamCodec.of(
         (buf, e) -> {
            buf.writeVarLong(e.gameTime());
            buf.writeUtf(e.level());
            String msg = e.message();
            if (msg != null && msg.length() > MAX_LOG_MESSAGE_CHARS) {
               msg = msg.substring(0, MAX_LOG_MESSAGE_CHARS - 1) + "…";
            }
            buf.writeUtf(msg == null ? "" : msg, MAX_LOG_MESSAGE_CHARS);
         },
         buf -> new LogEntry(buf.readVarLong(), buf.readUtf(), buf.readUtf(MAX_LOG_MESSAGE_CHARS))
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, PinSummary> PIN_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUtf(p.id());
            buf.writeUtf(p.text(), 1024);
            buf.writeUtf(p.status());
            buf.writeVarLong(p.createdDay());
         },
         buf -> new PinSummary(buf.readUtf(), buf.readUtf(1024), buf.readUtf(), buf.readVarLong())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, TodoSummary> TODO_CODEC =
      StreamCodec.of(
         (buf, t) -> {
            buf.writeUtf(t.id());
            buf.writeUtf(t.text(), 1024);
            buf.writeUtf(t.counterparty());
            buf.writeUtf(t.status());
            buf.writeVarLong(t.createdDay());
         },
         buf -> new TodoSummary(buf.readUtf(), buf.readUtf(1024), buf.readUtf(), buf.readUtf(), buf.readVarLong())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, ItemCount> ITEM_CODEC =
      StreamCodec.of(
         (buf, i) -> {
            buf.writeUtf(i.itemId());
            buf.writeVarInt(i.count());
            // trend is a tiny signed byte (-1 / 0 / +1) but writeByte
            // gives us a clean wire encoding either way.
            buf.writeByte((byte) Integer.signum(i.trend()));
         },
         buf -> new ItemCount(buf.readUtf(), buf.readVarInt(), buf.readByte())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, ResourceLoc> RESLOC_CODEC =
      StreamCodec.of(
         (buf, r) -> { buf.writeUtf(r.itemId()); buf.writeLong(r.packedPos()); buf.writeVarInt(r.count()); },
         buf -> new ResourceLoc(buf.readUtf(), buf.readLong(), buf.readVarInt())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, ProductionTarget> TARGET_CODEC =
      StreamCodec.of(
         (buf, t) -> {
            buf.writeUtf(t.itemId());
            buf.writeVarInt(t.min());
            buf.writeVarInt(t.max());
            buf.writeBoolean(t.active());
         },
         buf -> new ProductionTarget(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, TradeOfferView> TRADE_OFFER_CODEC =
      StreamCodec.of(
         (buf, o) -> {
            buf.writeUtf(o.id());
            buf.writeUtf(o.archetypeName());
            buf.writeUtf(o.tierName());
            buf.writeUtf(o.requestItemId());
            buf.writeVarInt(o.requestCount());
            buf.writeVarInt(o.paymentEmeralds());
            buf.writeVarInt(o.daysRemaining());
            buf.writeUtf(o.flavorBlurb() == null ? "" : o.flavorBlurb(), 256);
         },
         buf -> new TradeOfferView(
            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256))
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, ParcelSummary> PARCEL_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUtf(p.id());
            buf.writeUUID(p.ownerUuid());
            buf.writeUtf(p.ownerName());
            buf.writeUtf(p.type());
            buf.writeLong(p.centerPos());
            buf.writeVarInt(p.sizeX());
            buf.writeVarInt(p.sizeZ());
            buf.writeVarLong(p.createdDay());
            buf.writeVarInt(p.ripeCrops());
            buf.writeVarInt(p.emptyFarmland());
            buf.writeVarInt(p.tillableTiles());
            buf.writeVarInt(p.animalCount());
            buf.writeVarInt(p.animalBabies());
            buf.writeVarInt(p.unshornSheep());
         },
         buf -> new ParcelSummary(
            buf.readUtf(), buf.readUUID(), buf.readUtf(), buf.readUtf(),
            buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, StorageEntry> STORAGE_CODEC =
      StreamCodec.of(
         (buf, s) -> {
            buf.writeLong(s.packedPos());
            buf.writeUtf(s.blockKind());
            buf.writeUtf(s.lastUpdaterName());
            buf.writeVarLong(s.lastUpdatedTick());
            buf.writeVarInt(s.contents().size());
            for (ItemCount ic : s.contents()) ITEM_CODEC.encode(buf, ic);
            buf.writeUtf(s.label() == null ? "" : s.label(), 64);
         },
         buf -> {
            long pos = buf.readLong();
            String kind = buf.readUtf();
            String by = buf.readUtf();
            long when = buf.readVarLong();
            int n = buf.readVarInt();
            java.util.ArrayList<ItemCount> contents = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) contents.add(ITEM_CODEC.decode(buf));
            String label = buf.readUtf(64);
            return new StorageEntry(pos, kind, by, when, contents, label);
         }
      );

   private static final StreamCodec<RegistryFriendlyByteBuf, VillagerSummary> SUMMARY_CODEC =
      StreamCodec.of(
         (buf, s) -> {
            buf.writeUUID(s.uuid());
            buf.writeUtf(s.name());
            buf.writeUtf(s.role());
            buf.writeUtf(s.personaSeed());
            buf.writeUtf(s.backstory(), 4096);
            buf.writeBoolean(s.alive());
            buf.writeVarInt(s.llmCalls());
            buf.writeVarLong(s.inputTokens());
            buf.writeVarLong(s.outputTokens());
            buf.writeDouble(s.estCostUsd());
            buf.writeUtf(s.beliefs(), 4096);
            buf.writeVarInt(s.pinnedFacts().size());
            for (PinSummary p : s.pinnedFacts()) PIN_CODEC.encode(buf, p);
            buf.writeVarInt(s.memoryCount());
            buf.writeVarLong(s.lastCompactedDay());
            buf.writeVarInt(s.todos().size());
            for (TodoSummary td : s.todos()) TODO_CODEC.encode(buf, td);
            buf.writeLong(s.packedPos());
            buf.writeFloat(s.health());
            buf.writeFloat(s.maxHealth());
            buf.writeUtf(s.activity());
            buf.writeBoolean(s.playerSetHome());
            buf.writeBoolean(s.playerSetJob());
            buf.writeVarInt(s.inventory().size());
            for (ItemCount ic : s.inventory()) ITEM_CODEC.encode(buf, ic);
            buf.writeUtf(s.profession());
            buf.writeVarInt(s.hunger());
            buf.writeUtf(s.gender());
            buf.writeVarInt(s.ageDays());
         },
         buf -> {
            UUID id = buf.readUUID();
            String name = buf.readUtf();
            String role = buf.readUtf();
            String seed = buf.readUtf();
            String backstory = buf.readUtf(4096);
            boolean alive = buf.readBoolean();
            int calls = buf.readVarInt();
            long inT = buf.readVarLong();
            long outT = buf.readVarLong();
            double cost = buf.readDouble();
            String beliefs = buf.readUtf(4096);
            int n = buf.readVarInt();
            java.util.ArrayList<PinSummary> pins = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) pins.add(PIN_CODEC.decode(buf));
            int recentCount = buf.readVarInt();
            long lastCompact = buf.readVarLong();
            int tn = buf.readVarInt();
            java.util.ArrayList<TodoSummary> todos = new java.util.ArrayList<>(tn);
            for (int i = 0; i < tn; i++) todos.add(TODO_CODEC.decode(buf));
            long packedPos = buf.readLong();
            float hp = buf.readFloat();
            float maxHp = buf.readFloat();
            String act = buf.readUtf();
            boolean ph = buf.readBoolean();
            boolean pj = buf.readBoolean();
            int invN = buf.readVarInt();
            java.util.ArrayList<ItemCount> inv = new java.util.ArrayList<>(invN);
            for (int i = 0; i < invN; i++) inv.add(ITEM_CODEC.decode(buf));
            String profession = buf.readUtf();
            int hunger = buf.readVarInt();
            String gender = buf.readUtf();
            int ageDays = buf.readVarInt();
            return new VillagerSummary(id, name, role, seed, backstory, alive,
               calls, inT, outT, cost, beliefs, pins, recentCount, lastCompact, todos,
               packedPos, hp, maxHp, act, ph, pj, inv, profession, hunger, gender, ageDays);
         }
      );

   public static final StreamCodec<RegistryFriendlyByteBuf, TownStateUpdatePayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.townSquarePos);
            buf.writeUtf(p.townName);
            buf.writeUtf(p.openrouterStatus);
            buf.writeDouble(p.openrouterUsage);
            buf.writeDouble(p.openrouterLimit);
            buf.writeDouble(p.totalTownCostUsd);
            buf.writeVarInt(p.villagers.size());
            for (VillagerSummary s : p.villagers) SUMMARY_CODEC.encode(buf, s);
            buf.writeVarInt(p.townFacts.size());
            for (PinSummary tf : p.townFacts) PIN_CODEC.encode(buf, tf);
            buf.writeVarInt(p.log.size());
            for (LogEntry le : p.log) LOG_CODEC.encode(buf, le);
            buf.writeVarInt(p.storage.size());
            for (StorageEntry s : p.storage) STORAGE_CODEC.encode(buf, s);
            buf.writeVarInt(p.aggregateResources.size());
            for (ItemCount ic : p.aggregateResources) ITEM_CODEC.encode(buf, ic);
            buf.writeVarInt(p.resourceLocations.size());
            for (ResourceLoc rl : p.resourceLocations) RESLOC_CODEC.encode(buf, rl);
            buf.writeVarInt(p.productionTargets.size());
            for (ProductionTarget pt : p.productionTargets) TARGET_CODEC.encode(buf, pt);
            buf.writeVarInt(p.parcels.size());
            for (ParcelSummary ps : p.parcels) PARCEL_CODEC.encode(buf, ps);
            buf.writeVarInt(p.tradeOffers.size());
            for (TradeOfferView tov : p.tradeOffers) TRADE_OFFER_CODEC.encode(buf, tov);
            buf.writeVarInt(p.treasury.size());
            for (ItemCount ic : p.treasury) ITEM_CODEC.encode(buf, ic);
            buf.writeVarInt(p.populationAlive);
            buf.writeVarInt(p.populationTotal);
            buf.writeVarInt(p.tradePostCount);
            buf.writeVarInt(p.prestige);
            buf.writeVarInt(p.homeCount);
            buf.writeBoolean(p.hasTownHall);
            buf.writeVarInt(p.tavernCount);
            buf.writeVarInt(p.idleCount);
            buf.writeVarInt(p.workingCount);
            buf.writeVarInt(p.sleepingCount);
            buf.writeVarInt(p.birthsToday);
            buf.writeVarInt(p.deathsToday);
         },
         buf -> {
            long pos = buf.readLong();
            String name = buf.readUtf();
            String orStatus = buf.readUtf();
            double orUsage = buf.readDouble();
            double orLimit = buf.readDouble();
            double total = buf.readDouble();
            int n = buf.readVarInt();
            java.util.ArrayList<VillagerSummary> villagers = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) villagers.add(SUMMARY_CODEC.decode(buf));
            int m = buf.readVarInt();
            java.util.ArrayList<PinSummary> townFacts = new java.util.ArrayList<>(m);
            for (int i = 0; i < m; i++) townFacts.add(PIN_CODEC.decode(buf));
            int k = buf.readVarInt();
            java.util.ArrayList<LogEntry> log = new java.util.ArrayList<>(k);
            for (int i = 0; i < k; i++) log.add(LOG_CODEC.decode(buf));
            int sn = buf.readVarInt();
            java.util.ArrayList<StorageEntry> storage = new java.util.ArrayList<>(sn);
            for (int i = 0; i < sn; i++) storage.add(STORAGE_CODEC.decode(buf));
            int an = buf.readVarInt();
            java.util.ArrayList<ItemCount> agg = new java.util.ArrayList<>(an);
            for (int i = 0; i < an; i++) agg.add(ITEM_CODEC.decode(buf));
            int rn = buf.readVarInt();
            java.util.ArrayList<ResourceLoc> resLocs = new java.util.ArrayList<>(rn);
            for (int i = 0; i < rn; i++) resLocs.add(RESLOC_CODEC.decode(buf));
            int tn = buf.readVarInt();
            java.util.ArrayList<ProductionTarget> targets = new java.util.ArrayList<>(tn);
            for (int i = 0; i < tn; i++) targets.add(TARGET_CODEC.decode(buf));
            int pn = buf.readVarInt();
            java.util.ArrayList<ParcelSummary> parcels = new java.util.ArrayList<>(pn);
            for (int i = 0; i < pn; i++) parcels.add(PARCEL_CODEC.decode(buf));
            int ton = buf.readVarInt();
            java.util.ArrayList<TradeOfferView> tradeOffers = new java.util.ArrayList<>(ton);
            for (int i = 0; i < ton; i++) tradeOffers.add(TRADE_OFFER_CODEC.decode(buf));
            int treasN = buf.readVarInt();
            java.util.ArrayList<ItemCount> treasury = new java.util.ArrayList<>(treasN);
            for (int i = 0; i < treasN; i++) treasury.add(ITEM_CODEC.decode(buf));
            int popA = buf.readVarInt();
            int popT = buf.readVarInt();
            int tradePosts = buf.readVarInt();
            int prestige = buf.readVarInt();
            int homeCount = buf.readVarInt();
            boolean hasTownHall = buf.readBoolean();
            int tavernCount = buf.readVarInt();
            int idle = buf.readVarInt();
            int work = buf.readVarInt();
            int slp  = buf.readVarInt();
            int bToday = buf.readVarInt();
            int dToday = buf.readVarInt();
            return new TownStateUpdatePayload(pos, name, orStatus, orUsage, orLimit, total,
               villagers, townFacts, log, storage,
               agg, resLocs, targets, parcels, tradeOffers, treasury,
               popA, popT, tradePosts, prestige,
               homeCount, hasTownHall, tavernCount,
               idle, work, slp, bToday, dToday);
         }
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

   public BlockPos resolveBlockPos() { return BlockPos.of(this.townSquarePos); }
   public Optional<Double> usage() { return openrouterUsage < 0 ? Optional.empty() : Optional.of(openrouterUsage); }
   public Optional<Double> limit() { return openrouterLimit < 0 ? Optional.empty() : Optional.of(openrouterLimit); }
}
