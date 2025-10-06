package com.practice.urlPoller;


import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.Event.TIMER_EXPIRED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Distributor extends VerticleBase
{
  public static final String LINE_BREAK = "\n";
  public static final String NO_IPS_FOUND_FOR_TIMER_INTERVAL = "No IPs found for timer interval: ";
  public static final String COMPLETED_FPING_BATCH_IPS_FOR_INTERVAL = "Completed fping batch: %s IPs for %ss interval";
  public static final String FPING_BATCH_FAILED_FOR_INTERVAL = "Fping batch failed for %ss interval: %s";
  public static final String COMMA = ",";
  public static final String HASH = "#";
  public static final String LOADED_POLLING_INTERVALS = "Loaded %s polling intervals (GCD)";
  private static final Map<Integer, Integer> gcdTicks = new ConcurrentHashMap<>();
  private static Map<Integer, Set<String>> ipTable;
  private static Map<Integer, Map<Integer, Set<String>>> gcdMap;

  @Override
  public Future<?> start()
  {
    // Create a dedicated worker executor with explicit configuration
    // Pool size: 20 workers (can handle 20 concurrent pings)
    // Max execute time: 10 seconds (ping timeout is 5s, add buffer)

    vertx.eventBus()
         .consumer(TIMER_EXPIRED, message -> {
           var json = (JsonObject) message.body();
           var timer = json.getInteger(DATA);

           var gcdGroup = gcdMap.get(timer);

           if (gcdGroup == null || gcdGroup.isEmpty())
           {
             System.err.println(NO_IPS_FOUND_FOR_TIMER_INTERVAL + timer);
             return;
           }

           // Batch execution using fping for high performance
           // Single process handles all IPs in this interval group

           var tick = gcdTicks.get(timer);
           gcdTicks.put(timer, ++tick);

           var future = FpingWorker.work(vertx, gcdGroup, timer, tick);

           future.onComplete(_future -> {
                   if (_future.succeeded())
                   {
                     System.out.printf(COMPLETED_FPING_BATCH_IPS_FOR_INTERVAL, gcdGroup.size(), timer);
                   } else
                   {
                     System.err.printf(FPING_BATCH_FAILED_FOR_INTERVAL, timer, _future.cause()
                                                                                      .getMessage());
                   }
                 })
                 .onFailure(Throwable::printStackTrace);

         });

    vertx.eventBus()
         .consumer(CONFIG_LOADED, message -> {
           // Guard against multiple CONFIG_LOADED events

           var json = (JsonObject) message.body();
           var data = json.getString(DATA);
           var urls = data.split(LINE_BREAK);
           ipTable = new HashMap<>();

           // Track all IPs to detect duplicates across different intervals

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
             var pollTime = Integer.parseInt(temp[1].strip());


             ipTable.computeIfAbsent(pollTime, k -> new HashSet<>())
                    .add(ip);
           }

           var pollTimes = new TreeSet<>(ipTable.keySet());
           gcdMap = new HashMap<>();

           for (var pt : pollTimes)
           {
             var devideableList = gcdMap.keySet()
                                        .stream()
                                        .filter(pollT -> pt % pollT == 0)
                                        .toList()
               ;

             if (!devideableList.isEmpty())
             {
               var dividableKey = devideableList.get(new Random().nextInt(devideableList.size()));
               gcdMap.get(dividableKey)
                     .put(pt / dividableKey, ipTable.get(pt));

             } else
             {
               gcdMap.computeIfAbsent(pt, k -> new HashMap<>())
                     .put(1, ipTable.get(pt));
             }
           }

           gcdMap.keySet()
                 .forEach(Key -> gcdTicks.put(Key, 0));


           System.out.printf(LOADED_POLLING_INTERVALS, gcdMap.size());


           // Create periodic timers for each poll interval
           for (var gcdToMultipleToIpSet : gcdMap.entrySet())
           {
             var pollInterval = gcdToMultipleToIpSet.getKey();

             // Create and cache the timer message
             JsonObject timerMsg = new JsonObject().put(DATA, pollInterval);

             var intervalMs = pollInterval * 1000L;
             var initialDelayMs = intervalMs / 2;

             // System.out.println("Starting timer for " + gcdToMultipleToIpSet.getValue().size() + " IPs at " + pollInterval + "s interval");
             System.out.printf("Starting timer for %s IP(groups)s at %ss interval\n", gcdToMultipleToIpSet.getValue()
                                                                                                          .size(), pollInterval);

             // Reuse the cached message
             vertx.setPeriodic(initialDelayMs, intervalMs, id -> vertx.eventBus()
                                                                      .publish(TIMER_EXPIRED, timerMsg));

           }
         });
    return Future.succeededFuture();
  }

}

