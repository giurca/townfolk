package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One unit of retrievable memory. Replaces the bounded {@code recentEvents}
 * layer entirely — RAG retrieval over this list is what surfaces past
 * experiences in dialogue and exchange prompts.
 *
 *   id           UUID, never reused
 *   kind         "dialogue" | "event" | "exchange_summary" | "reflection"
 *                | "world" | "place" | "brain" | "action"
 *   createdDay   in-game day (gameTime / 24000) when written
 *   text         the human-readable memory ("Player Dev gave me 3 wheat.")
 *   vector       L2-normalised embedding (target dim 512); empty until the
 *                async embed call lands, at which point a withVector update
 *                fills it in
 *
 * The vector is stored normalised so retrieval can use a plain dot product
 * instead of cosine similarity at query time.
 */
public record EmbeddedEntry(String id, String kind, long createdDay,
                            String text, List<Float> vector) {

   public static final Codec<EmbeddedEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.STRING.fieldOf("id").forGetter(EmbeddedEntry::id),
      Codec.STRING.optionalFieldOf("kind", "event").forGetter(EmbeddedEntry::kind),
      Codec.LONG.optionalFieldOf("day", 0L).forGetter(EmbeddedEntry::createdDay),
      Codec.STRING.fieldOf("text").forGetter(EmbeddedEntry::text),
      Codec.FLOAT.listOf().optionalFieldOf("v", List.of()).forGetter(EmbeddedEntry::vector)
   ).apply(instance, EmbeddedEntry::new));

   public static EmbeddedEntry pending(String kind, long day, String text) {
      return new EmbeddedEntry(UUID.randomUUID().toString(), kind, day, text, List.of());
   }

   public EmbeddedEntry withVector(float[] v) {
      Float[] boxed = new Float[v.length];
      for (int i = 0; i < v.length; i++) boxed[i] = v[i];
      return new EmbeddedEntry(id, kind, createdDay, text, List.of(boxed));
   }

   public boolean isPending() { return vector.isEmpty(); }

   /** L2-normalised dot product with another already-normalised vector. */
   public double similarity(float[] q) {
      if (vector.isEmpty() || q.length != vector.size()) return Double.NEGATIVE_INFINITY;
      double sum = 0;
      for (int i = 0; i < q.length; i++) sum += vector.get(i) * q[i];
      return sum;
   }
}
