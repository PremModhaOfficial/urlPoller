package com.practice.urlPoller;


import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.practice.urlPoller.Constants.JsonFields.DATA;
import static com.practice.urlPoller.Events.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Events.Event.TIMER_EXPIRED;

public class Distributor extends VerticleBase
{
  public static final String LINE_BREAK = "\n";
  public static final String EXECUTOR_NAME = "-Motadata-";
  private static Map<Byte, Set<String>> ipTable;

  @Override
  public Future<?> start()
  {
    // Create a dedicated worker executor with explicit configuration
    // Pool size: 20 workers (can handle 20 concurrent pings)
    // Max execute time: 10 seconds (ping timeout is 5s, add buffer)

    vertx.eventBus().consumer(TIMER_EXPIRED, message -> {
      var json = (JsonObject) message.body();
      var timer = json.getInteger(DATA).byteValue();

      var set = ipTable.get(timer);

      if (set == null || set.isEmpty())
      {
        System.err.println("No IPs found for timer interval: " + timer);
        return;
      }

      // Batch execution using fping for high performance
      // Single process handles all IPs in this interval group
      var cf = FpingBatchWorker.work(vertx, set, timer);
      
      Future.fromCompletionStage(cf)
        .onComplete(_future -> {
          if (_future.succeeded())
          {
            System.out.println("Completed fping batch: " + set.size() + " IPs for " + timer + "s interval");
          }
          else
          {
            System.err.println("Fping batch failed for " + timer + "s interval: " + _future.cause().getMessage());
          }
        })
        .onFailure(Throwable::printStackTrace);

    });

    vertx.eventBus().consumer(CONFIG_LOADED, message -> {
      // Guard against multiple CONFIG_LOADED events

      var json = (JsonObject) message.body();
      var data = json.getString(DATA);
      var urls = data.split(LINE_BREAK);
      ipTable = new HashMap<>();

      for (var url : urls)
      {
        if (url.isBlank()) continue; // Skip empty lines
        
        // Skip comment lines
        String trimmed = url.trim();
        if (trimmed.startsWith("#")) continue;
        
        var temp = url.split(",");
        if (temp.length < 2) {
          System.err.println("Skipping invalid line (missing interval): " + url);
          continue;
        }
        
        var ip = temp[0].strip();
        var pollTime = Byte.parseByte(temp[1].strip());

        if (!ipTable.containsKey(pollTime)) ipTable.put(pollTime, new HashSet<>());

        ipTable.get(pollTime).add(ip);
      }

      System.out.println("Loaded " + ipTable.size() + " polling intervals");

      // Create periodic timers for each poll interval
      for (var ipByTime : ipTable.entrySet())
      {
        byte pollInterval = ipByTime.getKey();
        long intervalMs = pollInterval * 1000L;
        long initialDelayMs = intervalMs / 2;

        System.out.println("Starting timer for " + ipByTime.getValue().size() + " IPs at " + pollInterval + "s interval");

        vertx.setPeriodic(initialDelayMs, intervalMs, id -> vertx.eventBus().publish(TIMER_EXPIRED, new JsonObject().put(DATA, pollInterval)));
      }
    });
    return Future.succeededFuture();
  }

}

