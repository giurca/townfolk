package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;

/**
 * A short-lived commitment with a real lifecycle, separate from {@link PinnedFact}.
 *
 *   id            stable identifier (for completion / lookup)
 *   text          the commitment itself ("fix Claus's fence by midday")
 *   counterparty  the other party — usually the villager or player who's
 *                 owed (or owing) — empty if self-set
 *   status        "open" | "done" | "abandoned"
 *   createdDay    in-game (gameTime/24000) day the todo was created
 *
 * Auto-decay: after a configurable age, open todos are folded into the
 * villager's beliefs blob as "I never did get around to..." and removed.
 */
public record Todo(String id, String text, String counterparty, String status, long createdDay) {

   public static final Codec<Todo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.STRING.fieldOf("id").forGetter(Todo::id),
      Codec.STRING.fieldOf("text").forGetter(Todo::text),
      Codec.STRING.optionalFieldOf("counterparty", "").forGetter(Todo::counterparty),
      Codec.STRING.optionalFieldOf("status", "open").forGetter(Todo::status),
      Codec.LONG.optionalFieldOf("createdDay", 0L).forGetter(Todo::createdDay)
   ).apply(instance, Todo::new));

   public static Todo create(String text, String counterparty, long day) {
      return new Todo(UUID.randomUUID().toString(), text, counterparty == null ? "" : counterparty, "open", day);
   }

   public Todo withStatus(String newStatus) {
      return new Todo(id, text, counterparty, newStatus, createdDay);
   }

   public boolean isOpen() { return "open".equals(status); }
}
