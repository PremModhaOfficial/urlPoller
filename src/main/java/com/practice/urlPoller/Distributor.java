package com.practice.urlPoller;


import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.Event.TIMER_EXPIRED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Distributor extends VerticleBase
{
    public static final String LINE_BREAK = "\n";
    public static final String COMMA = ",";
    public static final String HASH = "#";
    private static final Logger logger = LoggerFactory.getLogger(Distributor.class);

    private static final Map<String, Integer> ipsDATA = new HashMap<>();
    private static final Map<String, Integer> ips = new HashMap<>();
    private static final int TIME_INTERVAL_SEC = 5;
    private static final long TIME_INTERVAL_MS = TIME_INTERVAL_SEC * 1000L;


    @Override

    public Future<?> start()
    {


        // Create a dedicated worker executor with explicit configuration
        // Pool size: 20 workers (can handle 20 concurrent pings)
        // Max execute time: 10 seconds (ping timeout is 5s, add buffer)

        vertx.eventBus()
            .<JsonObject>localConsumer(TIMER_EXPIRED, message -> {
                                           message.body();

                                           // Get expired entries using headMap (O(log n) lookup)
                                           //                                   var expiredMap = timeOutMap.headMap(currentTime, true);


                                           // Collect expired entries to avoid ConcurrentModificationException

                                           // Collect all IPs to poll in this cycle
                                           var allExpiredIPs = getExpiredIps();

                                           // Process and reschedule each expired entry

                                           logger.debug("Polling {} IPs", allExpiredIPs.size());

                                           // Batch ping once with all collected IPs
                                           if (!allExpiredIPs.isEmpty())
                                           {
                                               FpingWorker.work(vertx, allExpiredIPs, 5)
                                                   .onFailure(throwable -> logger.error("FpingWorker failed for {} IPs", allExpiredIPs.size(), throwable));
                                           }
                                       }
            );

        vertx.eventBus()
            .<JsonObject>localConsumer(CONFIG_LOADED, message -> {
                                           logger.info("CONFIG_LOADED event received, parsing IPs...");
                                           var json = message.body();
                                           var data = json.getString(DATA);
                                           var urls = data.split(LINE_BREAK);
                                           //                                   var ipTable = new HashMap<Integer, Set<String>>();

                                           // Track all IPs to detect duplicates across different intervals
                                           var totalLines = 0;
                                           var validLines = 0;
                                           var commentLines = 0;
                                           var invalidLines = 0;

                                           for (var url : urls)
                                           {
                                               totalLines++;
                                               if (url.isBlank()) continue; // Skip empty lines

                                               // Skip comment lines
                                               var trimmed = url.trim();
                                               if (trimmed.startsWith(HASH))
                                               {
                                                   commentLines++;
                                                   continue;
                                               }

                                               var temp = url.split(COMMA);
                                               if (temp.length < 2)
                                               {
                                                   logger.warn("Skipping invalid line (missing interval): {}", url);
                                                   invalidLines++;
                                                   continue;
                                               }

                                               try
                                               {
                                                   var ip = temp[0].strip();
                                                   var pollTimeStr = temp[1].strip();
                                                   var pollTime = Integer.parseInt(pollTimeStr);

                                                   // Validate poll time is positive
                                                   if (pollTime <= 0)
                                                   {
                                                       logger.warn("Skipping invalid poll time (must be > 0): {}", url);
                                                       invalidLines++;
                                                       continue;
                                                   }

                                                   // Check for duplicate IPs across intervals
                                                   if (ips.containsKey(ip))
                                                   {
                                                       logger.warn("Duplicate IP detected across intervals: {}", ip);
                                                   }

                                                   logger.debug("Parsed IP: {} -> {}s", ip, pollTime);
                                                   ips.put(ip, pollTime);
                                                   validLines++;
                                               } catch (NumberFormatException e)
                                               {
                                                   logger.warn("Skipping invalid line (non-integer interval): {}", url);
                                                   invalidLines++;
                                               }
                                           }

                                           logger.info("Parsed {} total lines: {} valid, {} comments, {} invalid", totalLines, validLines, commentLines, invalidLines);
                                           //                                   logger.info("Tracking {} unique IPs across {} poll intervals", ips.size(), ips.size());

                                           ipsDATA.putAll(ips);


                                           vertx.setPeriodic(TIME_INTERVAL_MS, id -> vertx.eventBus()
                                               .publish(TIMER_EXPIRED, new JsonObject().put(DATA, System.currentTimeMillis()))
                                           );

                                       }
            );
        return Future.succeededFuture();
    }

    private Set<String> getExpiredIps()
    {
        var expired = new HashSet<String>();
        ips.entrySet()
            .forEach(entry -> {
                var newVal = entry.getValue() - TIME_INTERVAL_SEC;
                if (newVal <= 0)
                {
                    expired.add(entry.getKey());
                    newVal = ipsDATA.get(entry.getKey());
                }
                entry.setValue(newVal);
            });
        return expired;
    }

}

