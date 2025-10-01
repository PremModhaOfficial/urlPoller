package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.practice.urlPoller.Constants.JsonFields.*;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

public class FileWriter extends VerticleBase
{
  private static final String SEPARATOR = "||";
  private static final String FILE_PARENT = "stats/";
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
  private static final String CSV_EXTENSION = ".csv";
  
  // CSV header format
  private static final String CSV_HEADER = "Timestamp,EpochMs,IP,Status,PacketLoss,MinRTT_ms,AvgRTT_ms,MaxRTT_ms\n";
  
  // Track which files have been initialized with headers (thread-safe)
  private static final Set<String> initializedFiles = ConcurrentHashMap.newKeySet();

  static
  {
    new File(FILE_PARENT).mkdir();
  }

  @Override
  public Future<?> start()
  {
    vertx.eventBus().consumer(PROCESS_FAILED, message -> {
        var json = (JsonObject) message.body();
        var fileName = json.getString(FILE_NAME);
        var csvData = json.getString(DATA);  // Already in CSV format from FpingBatchWorker
        
        writeCsvRow(fileName, csvData);
        System.err.println("Ping failed: " + csvData);
      }
    );
    
    vertx.eventBus().consumer(PROCESS_SUCCEEDED, message -> {
        var json = (JsonObject) message.body();
        var fileName = json.getString(FILE_NAME);
        var csvData = json.getString(DATA);  // Already in CSV format from FpingBatchWorker
        
        writeCsvRow(fileName, csvData);
      }
    );

    return Future.succeededFuture();
  }

  /**
   * Write a CSV row to the file with timestamp.
   * Thread-safe: Each IP has its own file, and Vert.x file operations are async but sequential.
   * Adds CSV header on first write.
   * 
   * @param fileName IP address (used as filename)
   * @param csvRow CSV row data (IP,Status,Loss,Min,Avg,Max)
   */
  private void writeCsvRow(String fileName, String csvRow)
  {
    String filePath = FILE_PARENT + fileName + CSV_EXTENSION;
    
    // Check if file needs initialization with header (thread-safe)
    boolean needsHeader = initializedFiles.add(fileName);  // Returns true if newly added
    
    vertx.fileSystem().open(filePath, new OpenOptions().setAppend(true).setCreate(true))
      .onFailure(Throwable::printStackTrace)
      .onSuccess(file -> {
        
        Buffer buffer = Buffer.buffer();
        
        // Add header if this is the first write to this file
        if (needsHeader)
        {
          buffer.appendString(CSV_HEADER);
        }
        
        // Add timestamp + CSV data
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT));
        long epochMs = System.currentTimeMillis();
        String csvLine = String.format("%s,%d,%s\n", timestamp, epochMs, csvRow);
        
        buffer.appendString(csvLine);
        
        // Write and close
        file.write(buffer)
          .compose(v -> file.flush())
          .onComplete(writeResult -> file.close());
      });
  }

}
