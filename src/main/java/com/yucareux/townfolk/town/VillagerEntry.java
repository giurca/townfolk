package com.yucareux.townfolk.town;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * One record in the town's villager registry. Stores stable identity plus the
 * configuration the LLM layer eventually needs to drive behaviour. Mutated in
 * place by the {@link TownData} owner; serialised through NBT alongside the
 * block entity.
 */
public final class VillagerEntry {

   private final UUID uuid;
   private String name;
   private String role;
   private String personaSeed;
   private BlockPos spawnPos;
   private Optional<BlockPos> homePos;
   private boolean alive;

   public VillagerEntry(UUID uuid, String name, String role, String personaSeed, BlockPos spawnPos) {
      this.uuid = Objects.requireNonNull(uuid);
      this.name = Objects.requireNonNullElse(name, "Villager");
      this.role = Objects.requireNonNullElse(role, "wanderer");
      this.personaSeed = Objects.requireNonNullElse(personaSeed, "");
      this.spawnPos = Objects.requireNonNull(spawnPos);
      this.homePos = Optional.empty();
      this.alive = true;
   }

   public UUID uuid() { return this.uuid; }
   public String name() { return this.name; }
   public String role() { return this.role; }
   public String personaSeed() { return this.personaSeed; }
   public BlockPos spawnPos() { return this.spawnPos; }
   public Optional<BlockPos> homePos() { return this.homePos; }
   public boolean alive() { return this.alive; }

   public void setName(String name) { this.name = name; }
   public void setRole(String role) { this.role = role; }
   public void setPersonaSeed(String personaSeed) { this.personaSeed = personaSeed; }
   public void setHomePos(Optional<BlockPos> homePos) { this.homePos = homePos; }
   public void markDead() { this.alive = false; }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putUUID("uuid", this.uuid);
      tag.putString("name", this.name);
      tag.putString("role", this.role);
      tag.putString("personaSeed", this.personaSeed);
      tag.putInt("spawnX", this.spawnPos.getX());
      tag.putInt("spawnY", this.spawnPos.getY());
      tag.putInt("spawnZ", this.spawnPos.getZ());
      this.homePos.ifPresent(p -> {
         tag.putInt("homeX", p.getX());
         tag.putInt("homeY", p.getY());
         tag.putInt("homeZ", p.getZ());
         tag.putBoolean("homeSet", true);
      });
      tag.putBoolean("alive", this.alive);
      return tag;
   }

   public static VillagerEntry load(CompoundTag tag) {
      UUID uuid = tag.getUUID("uuid");
      BlockPos spawnPos = new BlockPos(tag.getInt("spawnX"), tag.getInt("spawnY"), tag.getInt("spawnZ"));
      VillagerEntry entry = new VillagerEntry(
         uuid,
         tag.getString("name"),
         tag.getString("role"),
         tag.getString("personaSeed"),
         spawnPos
      );
      if (tag.getBoolean("homeSet")) {
         entry.homePos = Optional.of(new BlockPos(
            tag.getInt("homeX"), tag.getInt("homeY"), tag.getInt("homeZ")
         ));
      }
      entry.alive = tag.contains("alive") ? tag.getBoolean("alive") : true;
      return entry;
   }
}
