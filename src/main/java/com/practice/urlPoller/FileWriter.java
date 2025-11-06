package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.practice.urlPoller.Constants.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Constants.Event.PROCESS_SUCCEEDED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;
import static com.practice.urlPoller.Constants.JsonFields.FILE_NAME;

public class FileWriter extends VerticleBase
{
  public static final String S_D_S = "%s,%d,%s\n";
  private static final Logger logger = LoggerFactory.getLogger(FileWriter.class);
  private static final String FILE_PARENT = "stats/";
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);
  private static final String CSV_EXTENSION = ".csv";
  // CSV header format
  private static final String CSV_HEADER = "Timestamp,EpochMs,Status,PacketLoss,MinRTT_ms,AvgRTT_ms,MaxRTT_ms\n";
  // Track which files have been initialized with headers (thread-safe)
  private static final Set<String> initializedFiles = ConcurrentHashMap.newKeySet();

  static
  {
    var parentDir = new File(FILE_PARENT);
    if (parentDir.mkdir())
    {
        logger.info("MADE DIRECTORY: {}", parentDir.getAbsolutePath());
    }
  }

  @Override
  public Future<?> start()
  {
    logger.info("FileWriter verticle started");
    logger.info("CSV output directory: {}", new File(FILE_PARENT).getAbsolutePath());

    vertx.eventBus()
         .consumer(PROCESS_FAILED, message -> {
           var json = (JsonObject) message.body();
           var ip = json.getString(FILE_NAME);

           logger.debug("[IP:{}] PROCESS_FAILED event received", ip);

           // Option C - Whitelist logging:
           if (LogConfig.shouldLogIp(ip)) {
             logger.trace("[IP:{}] Writing FAILED result to CSV", ip);
           }

           writeCsvRow(ip, json.getString(DATA));
         });

    vertx.eventBus()
         .consumer(PROCESS_SUCCEEDED, message -> {
           var json = (JsonObject) message.body();
           var ip = json.getString(FILE_NAME);

           // Option C - Whitelist logging:
           if (LogConfig.shouldLogIp(ip)) {
             logger.trace("[IP:{}] PROCESS_SUCCEEDED, writing to CSV", ip);
           }

           writeCsvRow(ip, json.getString(DATA));
         });

    return Future.succeededFuture();
  }

  /**
   * Write a CSV row to the file with timestamp.
   * Thread-safe: Each IP has its own file, and Vert.x file operations are async but sequential.
   * Adds CSV header on first write.
   *
   * @param fileName IP address (used as filename)
    * @param csvRow   CSV row data (Status,Loss,Min,Avg,Max)
   */
  private void writeCsvRow(String fileName, String csvRow)
  {
    var startNs = System.nanoTime();
    var sanitizedFileName = sanitizeFileName(fileName);
    var filePath = FILE_PARENT + sanitizedFileName + CSV_EXTENSION;

    // Check if file needs initialization with header (thread-safe)
    var needsHeader = initializedFiles.add(sanitizedFileName);  // Returns true if newly added

    vertx.fileSystem()
         .open(filePath, new OpenOptions().setAppend(true)
                                          .setCreate(true))
         .onFailure(error -> logger.error("[IP:{}] File write failed: path={}, error={}",
             fileName, filePath, error.getMessage(), error))
         .onSuccess(file -> {
           var durationMs = (System.nanoTime() - startNs) / 1_000_000;

           if (LogConfig.shouldLogIp(fileName)) {
             logger.trace("[IP:{}] File opened: path={}, duration={}ms",
                 fileName, filePath, durationMs);
           }

           var buffer = Buffer.buffer();

           // Add header if this is the first write to this file
           if (needsHeader)
           {
             buffer.appendString(CSV_HEADER);
           }

           // Add timestamp + CSV data
           var timestamp = LocalDateTime.now()
                                        .format(TIMESTAMP_FORMATTER);
           var epochMs = System.currentTimeMillis();
           var csvLine = String.format(S_D_S, timestamp, epochMs, csvRow);

           buffer.appendString(csvLine);

           // Write and close
           file.write(buffer)
               .compose(v -> file.flush())
               .onComplete(writeResult -> {
                 var totalDurationMs = (System.nanoTime() - startNs) / 1_000_000;

                 if (LogConfig.shouldLogIp(fileName)) {
                   logger.trace("[IP:{}] Write completed: bytes={}, duration={}ms",
                       fileName, buffer.length(), totalDurationMs);
                 }

                 file.close();
               });
         })
    ;
  }

  /**
   * Sanitize a filename by removing/replacing characters that are invalid for file systems.
   * Handles URLs like "<a href="http://192.168.1.1">...</a>" -> "http___192.168.1.1"
   *
   * @param input The input string (IP or URL)
   * @return Sanitized filename safe for file systems
   */
  private String sanitizeFileName(String input)
  {
    if (input == null || input.isEmpty()) {
      return "unknown";
    }

    // Replace problematic characters with underscores
    return input.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll(":", "_")  // Special handling for colon (common in URLs)
                .replaceAll("\\s+", "_") // Replace spaces with underscores
                .replaceAll("_+", "_")  // Replace multiple underscores with single
                .replaceAll("^_|_$", ""); // Remove leading/trailing underscores
  }

}
