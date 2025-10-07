package com.practice.urlPoller;


import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.Event.TIMER_EXPIRED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Distributor extends VerticleBase
{
  public static final String LINE_BREAK = "\n";
  public static final String COMMA = ",";
  public static final String HASH = "#";
  private static final Logger logger = LoggerFactory.getLogger(Distributor.class);
  private static final NavigableMap<Long, Map<Integer, Set<String>>> timeOutMap = new TreeMap<>();
  private static final Set<String> allTrackedIPs = ConcurrentHashMap.newKeySet();


  @Override

  public Future<?> start()
  {
    // Create a dedicated worker executor with explicit configuration
    // Pool size: 20 workers (can handle 20 concurrent pings)
    // Max execute time: 10 seconds (ping timeout is 5s, add buffer)

    vertx.eventBus()
         .<JsonObject>localConsumer(TIMER_EXPIRED, message -> {
           var json = message.body();
           var currentTime = json.getLong(DATA);

           // Get expired entries using headMap (O(log n) lookup)
           var expiredMap = timeOutMap.headMap(currentTime, true);

           if (expiredMap.isEmpty())
           {
             logger.trace("No expired IPs at {}", currentTime);
             return;
           }

           // Collect expired entries to avoid ConcurrentModificationException
           var expiredEntries = new ArrayList<>(expiredMap.entrySet());

           // Collect all IPs to poll in this cycle
           var allExpiredIPs = new HashSet<String>();

           // Process and reschedule each expired entry
           for (var expiredEntry : expiredEntries)
           {
             var expiryTime = expiredEntry.getKey();
             var pollTimeMap = expiredEntry.getValue();

             // Remove the expired entry
             timeOutMap.remove(expiryTime);

             // Reschedule each poll time group
             for (var ptEntry : pollTimeMap.entrySet())
             {
               var pollTime = ptEntry.getKey();
               var ipSet = ptEntry.getValue();

               // Collect IPs for batch processing
               allExpiredIPs.addAll(ipSet);

               // Calculate new expiry time
               var newExpiry = currentTime + pollTime * 1000L;

               // Reuse existing IP sets - no new map creation unless key doesn't exist
               timeOutMap.computeIfAbsent(newExpiry, k -> new HashMap<>())
                         .put(pollTime, ipSet);  // Reuse the same ipSet reference
             }
           }

           logger.debug("Polling {} IPs from {} expired entries", allExpiredIPs.size(), expiredEntries.size());

           // Batch ping once with all collected IPs
           if (!allExpiredIPs.isEmpty())
           {
             FpingWorker.work(vertx, allExpiredIPs, 5, 0)
                        .onFailure(throwable -> logger.error("FpingWorker failed for {} IPs", allExpiredIPs.size(), throwable));
           }
         });

    vertx.eventBus()
         .<JsonObject>localConsumer(CONFIG_LOADED, message -> {
           logger.info("CONFIG_LOADED event received, parsing IPs...");

           var json = message.body();
           var data = json.getString(DATA);
           var urls = data.split(LINE_BREAK);
           var ipTable = new HashMap<Integer, Set<String>>();

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
               if (!allTrackedIPs.add(ip))
               {
                 logger.warn("Duplicate IP detected across intervals: {}", ip);
               }

               logger.debug("Parsed IP: {} -> {}s", ip, pollTime);
               ipTable.computeIfAbsent(pollTime, k -> new HashSet<>())
                      .add(ip);
               validLines++;
             } catch (NumberFormatException e)
             {
               logger.warn("Skipping invalid line (non-integer interval): {}", url);
               invalidLines++;
             }
           }

           logger.info("Parsed {} total lines: {} valid, {} comments, {} invalid", totalLines, validLines, commentLines, invalidLines);
           logger.info("Tracking {} unique IPs across {} poll intervals", allTrackedIPs.size(), ipTable.size());

           // Add IP TO the NavigableMaps
           var currentTime = System.currentTimeMillis();
           logger.info("Base timer starting at: {}", currentTime);

           ipTable.forEach((pollTime, setOfIp) -> {
             var next_expiry = pollTime * 1000L + currentTime;

             // Use computeIfAbsent to avoid creating unnecessary HashMap
             timeOutMap.computeIfAbsent(next_expiry, k -> new HashMap<>())
                       .put(pollTime, setOfIp);

             logger.debug("Scheduled {} IPs for {}s interval (expiry: {})", setOfIp.size(), pollTime, next_expiry);
           });

           // Calculate GCD for optimal timer interval
           var timerInterval = calculateOptimalTimerInterval(ipTable.keySet());
           logger.info("Setting periodic timer with {}ms interval", timerInterval);

           vertx.setPeriodic(timerInterval, id -> vertx.eventBus()
                                                       .publish(TIMER_EXPIRED, new JsonObject().put(DATA, System.currentTimeMillis())));

         });
    return Future.succeededFuture();
  }

  /**
   * Calculate the GCD of all poll intervals to determine optimal timer frequency.
   * Falls back to 5 seconds if no intervals exist.
   */
  private long calculateOptimalTimerInterval(Set<Integer> pollIntervals)
  {
    if (pollIntervals.isEmpty())
    {
      return 5000L; // Default 5 seconds
    }

    var intervals = pollIntervals.stream()
                                 .mapToLong(Integer::longValue)
                                 .toArray()
      ;
    var gcdResult = intervals[0];

    for (var i = 1; i < intervals.length; i++)
    {
      gcdResult = gcd(gcdResult, intervals[i]);
      if (gcdResult <= 5)
      {
        return 5000L;
      }
    }

    return gcdResult * 1000L; // Convert to milliseconds
  }

  /**
   * Calculate Greatest Common Divisor using Euclidean algorithm
   */
  private long gcd(long a, long b)
  {
    return b == 0 ? a : gcd(b, a % b);
  }

}

