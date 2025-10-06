package com.practice.urlPoller;


import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.Event.TIMER_EXPIRED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Distributor extends VerticleBase
{
  private static final Logger logger = LoggerFactory.getLogger(Distributor.class);

  public static final String LINE_BREAK = "\n";
  public static final String COMMA = ",";
  public static final String HASH = "#";
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
          .localConsumer(TIMER_EXPIRED, message -> {
            var json = (JsonObject) message.body();
            var timer = json.getInteger(DATA);

            // Generate batch ID for correlation
            var batchId = LogConfig.generateBatchId();

            var gcdGroup = gcdMap.get(timer);

            if (gcdGroup == null || gcdGroup.isEmpty())
            {
              logger.error("[Batch:{}] No IPs found for timer interval: {}s", batchId, timer);
              return;
            }

            // Calculate expected timing (for gap detection)
            var currentTick = gcdTicks.get(timer);
            var nextTick = currentTick + 1;
            gcdTicks.put(timer, nextTick);

            // Log which multipliers are active this tick:
            var activeMultipliers = gcdGroup.keySet().stream()
                                            .filter(integer -> nextTick % integer == 0)
                                            .toList();

            // Calculate expected IPs:
            var expectedIps = gcdGroup.entrySet().stream()
                                      .filter(e -> nextTick % e.getKey() == 0)
                                      .mapToInt(e -> e.getValue().size())
                                      .sum();

            logger.info("[Batch:{}] Timer fired: interval={}s, tick={}, activeMultipliers={}, expectedIps={}",
                batchId, timer, nextTick, activeMultipliers, expectedIps);

            // Option C - Per-IP Logging (with whitelist):
            if (logger.isTraceEnabled()) {
              for (var entry : gcdGroup.entrySet()) {
                if (nextTick % entry.getKey() == 0) {
                  var multiplier = entry.getKey();
                  var actualInterval = timer * multiplier;

                  for (var ip : entry.getValue()) {
                    if (LogConfig.shouldLogIp(ip)) {
                      logger.trace("[Batch:{}][IP:{}] Scheduled: gcd={}s, mult={}, interval={}s, tick={}",
                          batchId, ip, timer, multiplier, actualInterval, nextTick);
                    }
                  }
                }
              }
            }

            // Batch execution using fping for high performance
            var batchStartNs = System.nanoTime();

            FpingWorker.work(vertx, gcdGroup, timer, nextTick, batchId)
                       .onComplete(future -> {
                         var durationMs = (System.nanoTime() - batchStartNs) / 1_000_000;

                         if (future.succeeded())
                         {
                           var totalIps = gcdGroup.values()
                                                  .stream()
                                                  .mapToInt(Set::size)
                                                  .sum();
                           logger.info("[Batch:{}] Completed: totalIps={}, duration={}ms, rate={} IPs/sec",
                               batchId, totalIps, durationMs,
                               durationMs > 0 ? (totalIps * 1000.0 / durationMs) : 0);
                         }
                         else
                         {
                           logger.error("[Batch:{}] Failed: interval={}s, cause={}",
                               batchId, timer, future.cause().getMessage(), future.cause());
                         }
                       })
                       .onFailure(throwable -> logger.error("[Batch:{}] Unexpected failure in batch processing",
                           batchId, throwable));
          });

    vertx.eventBus()
          .localConsumer(CONFIG_LOADED, message -> {
            logger.info("CONFIG_LOADED event received, parsing IPs...");

            var json = (JsonObject) message.body();
            var data = json.getString(DATA);
            var urls = data.split(LINE_BREAK);
            ipTable = new HashMap<>();

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
              if (trimmed.startsWith(HASH)) {
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

              var ip = temp[0].strip();
              var pollTime = Integer.parseInt(temp[1].strip());

              logger.debug("Parsed IP: {} -> {}s", ip, pollTime);
              ipTable.computeIfAbsent(pollTime, k -> new HashSet<>())
                     .add(ip);
              validLines++;
            }

            logger.info("Parsed {} total lines: {} valid, {} comments, {} invalid",
                totalLines, validLines, commentLines, invalidLines);

            var pollTimes = new TreeSet<>(ipTable.keySet());
            gcdMap = new HashMap<>();

            // Log IP distribution before GCD calculation
            var totalIps = ipTable.values().stream().mapToInt(Set::size).sum();
            logger.info("Parsed {} IPs across {} unique poll intervals", totalIps, ipTable.size());

            for (var entry : ipTable.entrySet()) {
              logger.info("Poll interval {}s: {} IPs", entry.getKey(), entry.getValue().size());
            }

            // GCD calculation
            for (var pt : pollTimes)
            {
              var devideableList = gcdMap.keySet()
                                         .stream()
                                         .filter(pollT -> pt % pollT == 0)
                                         .toList();

              if (!devideableList.isEmpty())
              {
                var dividableKey = devideableList.get(new Random().nextInt(devideableList.size()));
                gcdMap.get(dividableKey)
                      .put(pt / dividableKey, ipTable.get(pt));
                logger.debug("GCD: {}s grouped under {}s (multiplier={})",
                    pt, dividableKey, pt / dividableKey);

              } else
              {
                gcdMap.computeIfAbsent(pt, k -> new HashMap<>())
                      .put(1, ipTable.get(pt));
                logger.debug("GCD: {}s is new base interval", pt);
              }
            }

            gcdMap.keySet()
                  .forEach(Key -> gcdTicks.put(Key, 0));

            logger.info("GCD map created: {} base intervals (GCDs)", gcdMap.size());

            // Log GCD structure (Option B - Batch level):
            for (var gcdEntry : gcdMap.entrySet()) {
              var gcd = gcdEntry.getKey();
              var multiplierMap = gcdEntry.getValue();
              var totalIpsInGcd = multiplierMap.values().stream().mapToInt(Set::size).sum();

              logger.info("GCD={}s: {} IPs across {} multipliers",
                  gcd, totalIpsInGcd, multiplierMap.size());

              // Detailed multiplier breakdown:
              for (var multEntry : multiplierMap.entrySet()) {
                logger.debug("  GCD={}s, multiplier={} (interval={}s): {} IPs",
                    gcd, multEntry.getKey(), gcd * multEntry.getKey(),
                    multEntry.getValue().size());
              }
            }

            // Create periodic timers for each poll interval
            for (var gcdToMultipleToIpSet : gcdMap.entrySet())
            {
              var pollInterval = gcdToMultipleToIpSet.getKey();

              // Create and cache the timer message
              var timerMsg = new JsonObject().put(DATA, pollInterval);

              var intervalMs = pollInterval * 1000L;
              var initialDelayMs = intervalMs / 2;

              var totalIpsInTimer = gcdToMultipleToIpSet.getValue().values()
                                                        .stream().mapToInt(Set::size).sum();

              logger.info("Timer created: interval={}s ({}ms), initialDelay={}ms, IPs={}",
                  pollInterval, intervalMs, initialDelayMs, totalIpsInTimer);

              // Reuse the cached message
              vertx.setPeriodic(initialDelayMs, intervalMs, id -> vertx.eventBus()
                                                                       .publish(TIMER_EXPIRED, timerMsg));

            }
         });
    return Future.succeededFuture();
  }

}

