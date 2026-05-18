package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A single line in a villager's dialogue history.
 *
 * Role is "player" or "villager"; text is plain prose (no formatting tags).
 * Persisted as part of {@link LlmVillagerComponent} and round-tripped
 * through entity NBT, so conversations survive across world reloads.
 */
public record DialogueTurn(String role, String text) {

   public static final Codec<DialogueTurn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.STRING.fieldOf("role").forGetter(DialogueTurn::role),
      Codec.STRING.fieldOf("text").forGetter(DialogueTurn::text)
   ).apply(instance, DialogueTurn::new));
}
