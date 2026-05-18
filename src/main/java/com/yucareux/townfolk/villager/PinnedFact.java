package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;

/**
 * A long-term fact that should NEVER be summarized away. Injected verbatim
 * into the villager's system prompt every dialogue turn.
 *
 *   id         - stable identifier for player edits
 *   text       - the fact itself
 *   status     - "active", "resolved", "obsolete", or freeform suffix
 *   createdDay - in-game day when the fact was pinned
 *   playerEdited - true once the player has manually touched it; protects
 *                  from auto-pruning when over cap
 */
public record PinnedFact(String id, String text, String status, long createdDay, boolean playerEdited) {

   public static final Codec<PinnedFact> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.STRING.fieldOf("id").forGetter(PinnedFact::id),
      Codec.STRING.fieldOf("text").forGetter(PinnedFact::text),
      Codec.STRING.optionalFieldOf("status", "active").forGetter(PinnedFact::status),
      Codec.LONG.optionalFieldOf("createdDay", 0L).forGetter(PinnedFact::createdDay),
      Codec.BOOL.optionalFieldOf("playerEdited", false).forGetter(PinnedFact::playerEdited)
   ).apply(instance, PinnedFact::new));

   public static PinnedFact create(String text, long day) {
      return new PinnedFact(UUID.randomUUID().toString(), text, "active", day, false);
   }

   public PinnedFact withStatus(String newStatus) {
      return new PinnedFact(id, text, newStatus, createdDay, true);
   }
   public PinnedFact withText(String newText) {
      return new PinnedFact(id, newText, status, createdDay, true);
   }
}
