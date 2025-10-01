package com.practice.urlPoller;


import static com.practice.urlPoller.Constants.JsonFields.DATA;
import static com.practice.urlPoller.Events.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Events.Event.TIMER_EXPIRED;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

public class Distributor extends VerticleBase
{
  public static final String LINE_BREAK = "\n";
  public static final String NO_IPS_FOUND_FOR_TIMER_INTERVAL = "No IPs found for timer interval: ";
  public static final String COMPLETED_FPING_BATCH_IPS_FOR_INTERVAL = "Completed fping batch: %s IPs for %ss interval";
  public static final String FPING_BATCH_FAILED_FOR_INTERVAL = "Fping batch failed for %ss interval: %s";
  public static final String COMMA = ",";
  public static final String HASH = "#";
  public static final String LOADED_POLLING_INTERVALS = "Loaded %s polling intervals";
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
        System.err.println(NO_IPS_FOUND_FOR_TIMER_INTERVAL + timer);
        return;
      }

      // Batch execution using fping for high performance
      // Single process handles all IPs in this interval group
      var cf = FpingBatchWorker.work(vertx, set, timer);

      Future.fromCompletionStage(cf).onComplete(_future -> {
        if (_future.succeeded())
        {
          System.out.printf(COMPLETED_FPING_BATCH_IPS_FOR_INTERVAL, set.size(), timer);
        } else
        {
          System.err.printf(FPING_BATCH_FAILED_FOR_INTERVAL, timer, _future.cause().getMessage());
        }
      }).onFailure(Throwable::printStackTrace);

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
        if (trimmed.startsWith(HASH)) continue;

        var temp = url.split(COMMA);
        if (temp.length < 2)
        {
          System.err.println("Skipping invalid line (missing interval): " + url);
          continue;
        }

        var ip = temp[0].strip();
        var pollTime = Byte.parseByte(temp[1].strip());

        ipTable.computeIfAbsent(pollTime, k -> new HashSet<>()).add(ip);
      }

      System.out.printf(LOADED_POLLING_INTERVALS, ipTable.size());

      // Pre-create timer messages (reusable)
      Map<Byte, JsonObject> timerMessages = new HashMap<>();

      // Create periodic timers for each poll interval
      for (var ipByTime : ipTable.entrySet())
      {
        byte pollInterval = ipByTime.getKey();

        // Create and cache the timer message
        JsonObject timerMsg = new JsonObject().put(DATA, pollInterval);
        timerMessages.put(pollInterval, timerMsg);

        long intervalMs = pollInterval * 1000L;
        long initialDelayMs = intervalMs / 2;

        // System.out.println("Starting timer for " + ipByTime.getValue().size() + " IPs at " + pollInterval + "s interval");
        System.out.printf("Starting timer for %s IPs at %ss interval\n", ipByTime.getValue().size(), pollInterval);

        // Reuse the cached message
        vertx.setPeriodic(initialDelayMs, intervalMs, id -> vertx.eventBus().publish(TIMER_EXPIRED, timerMsg));
      }
    });
    return Future.succeededFuture();
  }

}

