package com.yucareux.townfolk.diag;

import com.yucareux.townfolk.Townfolk;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only verbose log written to {@code logs/townfolk-verbose.log}
 * (relative to the game working directory).
 *
 * Format: one block per event. Header starts with {@code === <iso-instant>
 * <CATEGORY> [metadata...] ===}. Body (verbatim, multi-line) follows.
 * Footer is the literal {@code === END ===} on its own line. Easy to
 * tail-read with {@code tail -f} and to grep by category or actor.
 *
 * Categories follow a {@code DOMAIN_EVENT} convention so grep narrows
 * cleanly: {@code SCHED_PHASE}, {@code PARCEL_PICK}, {@code DOOR_OPEN},
 * {@code BLOCK_TASK_DONE}, etc. See the source for the canonical list.
 *
 * Rotation: on session start, if the existing log exceeds
 * {@link #MAX_LOG_SIZE_BYTES} it is renamed with a date-stamped suffix
 * and a fresh log begins. Keeps any individual file readable while
 * preserving session history on disk.
 *
 * Thread-safe; writes serialise through this class.
 */
public final class VerboseLog {

   private static final Path PATH = Path.of("logs", "townfolk-verbose.log");
   /** Rotate the log when it exceeds this size at session start. 10 MB
    *  is roughly an evening of LLM-heavy play; anything bigger and the
    *  human-readable grep cycle slows down. */
   private static final long MAX_LOG_SIZE_BYTES = 10L * 1024 * 1024;

   private static BufferedWriter writer;
   private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

   private static synchronized void ensureOpen() {
      if (writer != null) return;
      try {
         if (PATH.getParent() != null) Files.createDirectories(PATH.getParent());
         // Rotate if existing log is over the threshold.
         if (Files.exists(PATH) && Files.size(PATH) > MAX_LOG_SIZE_BYTES) {
            Path rotated = PATH.resolveSibling("townfolk-verbose-"
               + LocalDateTime.now().format(FILE_STAMP) + ".log");
            try {
               Files.move(PATH, rotated, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException rotEx) {
               Townfolk.LOGGER.warn("VerboseLog rotation failed: {}", rotEx.toString());
            }
         }
         writer = Files.newBufferedWriter(PATH,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         writer.write("=== " + Instant.now() + " SESSION_START townfolk-verbose ===");
         writer.newLine();
         writer.write("=== END ===");
         writer.newLine();
         writer.flush();
      } catch (IOException e) {
         Townfolk.LOGGER.warn("VerboseLog open failed: {}", e.toString());
      }
   }

   public static synchronized void write(String category, String metadata, String body) {
      ensureOpen();
      if (writer == null) return;
      try {
         writer.write("=== " + Instant.now() + " " + category
            + (metadata == null || metadata.isBlank() ? "" : " " + metadata) + " ===");
         writer.newLine();
         if (body != null && !body.isEmpty()) {
            writer.write(body);
            if (!body.endsWith("\n")) writer.newLine();
         }
         writer.write("=== END ===");
         writer.newLine();
         writer.flush();
      } catch (IOException e) {
         Townfolk.LOGGER.warn("VerboseLog write failed: {}", e.toString());
      }
   }

   public static void write(String category, String body) { write(category, "", body); }

   private VerboseLog() {}
}
