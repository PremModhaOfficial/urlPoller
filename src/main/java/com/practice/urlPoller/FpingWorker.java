package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static com.practice.urlPoller.Constants.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Constants.Event.PROCESS_SUCCEEDED;
import static com.practice.urlPoller.Constants.JsonFields.*;

/**
 * Batch ping executor using fping for high-performance concurrent ping operations.
 * Executes multiple IPs in a single fping process, dramatically reducing thread count.
 * <p>
 * Thread-safe implementation using ConcurrentHashMap and Vert.x WorkerExecutor.
 * <p>
 * Performance: 1000 IPs in 10 interval groups = ~15 threads (vs 2000+ with individual ping)
 * Uses Vert.x named worker pool (6 threads) instead of unbounded custom executor.
 */
public class FpingWorker
{
  public static final String FPING = "fping";
  public static final String COUNT_FLAG = "-c";
  public static final String COUNT_ICMP = "3";
  public static final String TIMEOUT_FLAG = "-t";
  public static final String TIMEOUT_MILS = "200";
  public static final String QUIET_MODE_FLAG = "-q";
  public static final String FPING_BATCH = "fping-batch-";
  public static final String ERROR_READING_FPING_OUTPUT = "Error reading fping output";
  public static final String TIMEOUT_100 = ",TIMEOUT,100%,-,-,-";
  public static final String ERROR_100 = ",ERROR,100%,-,-,-";
  public static final int TIMEOUT = 4;
  private static final Logger logger = LoggerFactory.getLogger(FpingWorker.class);

  /**
   * Execute fping for a batch of IP addresses using Vert.x WorkerExecutor.
   * Thread-safe - uses ConcurrentHashMap and Vert.x worker pool.
   *
   * @param vertx        Vert.x instance for event bus
   * @param ipAddresses  Set of IPs to ping (can be 100s or 1000s)
   * @param pollInterval The polling interval for this batch (for logging)
   * @param batchId      Unique batch ID for correlation
   * @return Future containing map of IP -> JsonObject ping result
   */
  public static Future<Map<String, JsonObject>> work(Vertx vertx, Set<String> ipAddresses, Integer pollInterval, long batchId)
  {
    if (ipAddresses == null || ipAddresses.isEmpty())
    {
      logger.debug("[Batch:{}] Empty IP set for interval {}s", batchId, pollInterval);
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    var fpingPool = Main.getFpingWorkerPool();
    if (fpingPool == null)
    {
      logger.error("[Batch:{}] Worker pool not initialized", batchId);
      publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    logger.debug("[Batch:{}] Worker started: thread={}", batchId, Thread.currentThread()
                                                                        .getName());

    // Execute blocking fping operation on worker pool using Callable
    return fpingPool.executeBlocking(() -> {
      Thread.currentThread()
            .setName(FPING_BATCH + pollInterval);

      logger.debug("[Batch:{}] Flattened IPs: count={}", batchId, ipAddresses.size());

      // Option C - Log selected IPs (whitelist only):
      if (logger.isTraceEnabled())
      {
        for (var ip : ipAddresses)
        {
          if (LogConfig.shouldLogIp(ip))
          {
            logger.trace("[Batch:{}][IP:{}] Added to batch", batchId, ip);
          }
        }
      }

      // Build fping command with all IPs
      var command = buildFpingCommand(ipAddresses);
      logger.debug("[Batch:{}] Command: {} (args count={})",
        batchId, String.join(" ", command.subList(0, 6)) + " ...", command.size());

      var processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);  // Merge stderr into stdout

      var processStartNs = System.nanoTime();
      logger.debug("[Batch:{}] Starting fping process...", batchId);

      try
      {
        var proc = processBuilder.start();
        logger.info("[Batch:{}] Process started: pid={}, timeout={}s",
          batchId, proc.pid(), TIMEOUT);

        // CRITICAL FIX: Read output concurrently to prevent buffer deadlock
        // With 500+ IPs, fping generates ~50KB output. If stdout buffer fills,
        // fping blocks writing and waitFor() blocks forever, causing process reaper
        // to spin at high CPU. Solution: Read output asynchronously while process runs.

        // Start reading output immediately (non-blocking, uses ForkJoinPool.commonPool)
        var outputFuture = CompletableFuture.supplyAsync(() -> {
          var readStartNs = System.nanoTime();
          var output = readProcessOutput(proc);
          var readDurationMs = (System.nanoTime() - readStartNs) / 1_000_000;
          logger.debug("[Batch:{}] Async output read: size={}bytes, duration={}ms, thread={}",
            batchId, output.length(), readDurationMs, Thread.currentThread()
                                                            .getName());
          return output;
        });

        // Wait for process exit with timeout (uses Process.onExit() internally for efficiency)
        Process completedProc;
        try
        {
          completedProc = proc.onExit()
                              .orTimeout(TIMEOUT, TimeUnit.SECONDS)
                              .get();

          var processDurationMs = (System.nanoTime() - processStartNs) / 1_000_000;
          var exitCode = completedProc.exitValue();
          logger.info("[Batch:{}] Process completed: exitCode={}, duration={}ms",
            batchId, exitCode, processDurationMs);
        } catch (InterruptedException e)
        {
          // Thread interrupted - cleanup
          Thread.currentThread()
                .interrupt();
          proc.destroyForcibly();
          outputFuture.cancel(true);
          logger.warn("[Batch:{}] Process INTERRUPTED: duration={}ms",
            batchId, (System.nanoTime() - processStartNs) / 1_000_000);
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        } catch (ExecutionException e)
        {
          // Check if timeout or other failure
          if (e.getCause() instanceof TimeoutException)
          {
            // Timeout occurred - kill process
            proc.destroyForcibly();
            outputFuture.cancel(true); // Cancel output reading
            logger.warn("[Batch:{}] Process TIMEOUT: duration={}ms, IPs={}, killing process",
              batchId, (System.nanoTime() - processStartNs) / 1_000_000, ipAddresses.size());
          } else
          {
            // Process failed to start or crashed
            logger.error("[Batch:{}] Process execution failed: {}", batchId, e.getCause()
                                                                              .getMessage());
          }
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        }

        // Get the output (should be ready by now, process has exited)
        var parseStartNs = System.nanoTime();
        String output;
        try
        {
          output = outputFuture.get(1, TimeUnit.SECONDS); // Short timeout, should be immediate
        } catch (InterruptedException e)
        {
          Thread.currentThread()
                .interrupt();
          logger.error("[Batch:{}] Interrupted while reading process output", batchId);
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        } catch (TimeoutException e)
        {
          logger.error("[Batch:{}] Timeout reading process output after 1s (process exited but output not ready)", batchId);
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        } catch (ExecutionException e)
        {
          logger.error("[Batch:{}] Failed to read process output: {}", batchId, e.getCause()
                                                                                 .getMessage());
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        }

        var readDurationMs = (System.nanoTime() - parseStartNs) / 1_000_000;
        logger.debug("[Batch:{}] Output retrieved: size={}bytes, wait={}ms",
          batchId, output.length(), readDurationMs);

        if (output.isBlank())
        {
          logger.warn("[Batch:{}] Empty output from fping process", batchId);
          publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
          return new ConcurrentHashMap<>();
        }

        // Parse fping output (concurrent parsing with ConcurrentHashMap)
        parseStartNs = System.nanoTime();
        var results = FpingParser.parse(output, batchId);
        var parseDurationMs = (System.nanoTime() - parseStartNs) / 1_000_000;

        logger.info("[Batch:{}] Parsing completed: parsed={}/{}, duration={}ms",
          batchId, results.size(), ipAddresses.size(), parseDurationMs);

        // Publish events for each IP concurrently using parallel stream
        var publishStartNs = System.nanoTime();

        results.entrySet()
               .parallelStream()
               .forEach(entry -> {
                 publishResult(vertx, entry.getValue(), pollInterval, batchId);

                 // Option C - Per-IP result logging:
                 if (LogConfig.shouldLogIp(entry.getKey()))
                 {
                   var result = entry.getValue();
                   logger.trace("[Batch:{}][IP:{}] Parsed: status={}, loss={}%, rtt={}ms",
                     batchId, entry.getKey(),
                     result.getBoolean(PingResultUtil.SUCCESS) ? "UP" : "DOWN",
                     result.getInteger(PingResultUtil.PACKET_LOSS),
                     result.getBoolean(PingResultUtil.SUCCESS) ? result.getDouble(PingResultUtil.AVG_RTT) : -1);
                 }
               })
        ;

        var publishDurationMs = (System.nanoTime() - publishStartNs) / 1_000_000;
        logger.debug("[Batch:{}] Publishing completed: events={}, duration={}ms",
          batchId, results.size(), publishDurationMs);

        // Handle any IPs that weren't in the parsed results
        ipAddresses.stream()
                   .filter(ip -> !results.containsKey(ip))
                   .forEach(ip -> {
                     logger.warn("[Batch:{}][IP:{}] Missing from results, publishing ERROR",
                       batchId, ip);
                     publishMissingIp(vertx, ip, pollInterval, batchId);
                   })
        ;

        return results;

      } catch (IOException ioException)
      {
        logger.error("[Batch:{}] Failed to start process: {}", batchId, ioException.getMessage(), ioException);
        publishBatchTimeout(vertx, ipAddresses, pollInterval, batchId);
        return new ConcurrentHashMap<>();
      }
    }, false);  // ordered=false for better parallelism
  }

  /**
   * Build fping command with all IP addresses.
   * Command format: fping -c 1 -t 2000 -q IP1 IP2 IP3 ...
   */
  private static List<String> buildFpingCommand(Set<String> ipAddresses)
  {
    List<String> command = new ArrayList<>(ipAddresses.size() + 6);
    command.add(FPING);
    command.add(COUNT_FLAG);
    command.add(COUNT_ICMP);           // 1 ping per IP
    command.add(TIMEOUT_FLAG);
    command.add(TIMEOUT_MILS);        // 2 second timeout per IP
    command.add(QUIET_MODE_FLAG);          // Quiet mode - only show summary
    command.addAll(ipAddresses); // Add all IPs

    return command;
  }

  /**
   * Read all output from process (stdout + stderr merged).
   * Thread-safe.
   */
  private static String readProcessOutput(Process process)
  {
    var output = new StringBuilder(2048);

    try (var reader = new BufferedReader(process.inputReader(), 8192))
    {
      String line;
      while ((line = reader.readLine()) != null)
      {
        output.append(line)
              .append('\n');
      }
    } catch (IOException ioException)
    {
      System.err.println(ERROR_READING_FPING_OUTPUT);
      //      ioException.printStackTrace();
    }

    return output.toString();
  }

  /**
   * Publish event for a single ping result.
   * Thread-safe - can be called concurrently from parallel stream.
   */
  private static void publishResult(Vertx vertx, JsonObject result, int pollInterval, long batchId)
  {
    var json = new JsonObject().put(FILE_NAME, result.getString(PingResultUtil.IP))
                               .put(DATA, PingResultUtil.toCsvRow(result))  // CSV format for new output
                               .put(EXIT_CODE, result.getBoolean(PingResultUtil.SUCCESS) ? 0 : 1)
                               .put(POLL_INTERVAL, pollInterval)
                               .put("batchId", batchId)
      ;

    vertx.eventBus()
         .publish(result.getBoolean(PingResultUtil.SUCCESS) ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
  }

  /**
   * Publish timeout failure for all IPs in a batch.
   * Thread-safe - uses parallel stream.
   */
  private static void publishBatchTimeout(Vertx vertx, Set<String> ipAddresses, int pollInterval, long batchId)
  {
    logger.debug("[Batch:{}] Publishing timeout for {} IPs", batchId, ipAddresses.size());
    ipAddresses.parallelStream()
               .forEach(ip -> {
                 if (LogConfig.shouldLogIp(ip))
                 {
                   logger.trace("[Batch:{}][IP:{}] Publishing TIMEOUT event", batchId, ip);
                 }
                 vertx.eventBus()
                      .publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip)
                                                               .put(DATA, ip + TIMEOUT_100)
                                                               .put(EXIT_CODE, -1)
                                                               .put(POLL_INTERVAL, pollInterval)
                                                               .put("batchId", batchId));
               });
  }

  /**
   * Publish failure for IP that was missing from fping results.
   * Thread-safe.
   */
  private static void publishMissingIp(Vertx vertx, String ip, int pollInterval, long batchId)
  {
    vertx.eventBus()
         .publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip)
                                                  .put(DATA, ip + ERROR_100)
                                                  .put(EXIT_CODE, -1)
                                                  .put(POLL_INTERVAL, pollInterval)
                                                  .put("batchId", batchId));
  }

}
