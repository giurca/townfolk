package com.yucareux.townfolk.llm;

import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.DialogueTurn;
import com.yucareux.townfolk.villager.EmbeddedEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DialogueGenerator {

   public static CompletableFuture<LlmClient.LlmResult> reply(
         VillagerEntry entry, LlmVillagerComponent component, TownData town,
         long currentDay, List<DialogueTurn> history, String worldSense,
         List<EmbeddedEntry> retrievedMemories) {

      String system = PromptBuilder.dialogueSystemPrompt(entry, component, town, currentDay, retrievedMemories);
      system = PromptBuilder.withWorldSense(system, worldSense);

      List<LlmClient.Message> messages = new ArrayList<>();
      messages.add(new LlmClient.Message("system", system));
      for (DialogueTurn t : history) {
         messages.add(new LlmClient.Message(
            "player".equals(t.role()) ? "user" : "assistant",
            t.text()
         ));
      }
      return LlmClient.get().chat(TownfolkConfig.COMMON.dialogueModel.get(), messages);
   }

   private DialogueGenerator() {}
}
