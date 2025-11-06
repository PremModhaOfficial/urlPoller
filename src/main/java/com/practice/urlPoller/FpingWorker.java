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
   * @return Future containing map of IP -> JsonObject ping result
   */
  public static Future<Map<String, JsonObject>> work(Vertx vertx,
                                                     Set<String> ipAddresses,
                                                     Integer pollInterval)
  {
    if (ipAddresses == null || ipAddresses.isEmpty())
    {
      logger.debug("Empty IP set for interval {}s", pollInterval);
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    var fpingPool = Main.getFpingWorkerPool();
    if (fpingPool == null)
    {
      logger.error("Worker pool not initialized");
      publishBatchTimeout(vertx, ipAddresses, pollInterval);
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    logger.debug("Worker started: thread={}", Thread.currentThread()
      .getName()
    );

    // Execute blocking fping operation on worker pool using Callable
    return fpingPool.executeBlocking(() -> {
                                       Thread.currentThread()
                                         .setName(FPING_BATCH + pollInterval);

                                       logger.debug("Flattened IPs: count={}", ipAddresses.size());

                                       // Option C - Log selected IPs (whitelist only):
                                       if (logger.isTraceEnabled())
                                       {
                                         for (var ip : ipAddresses)
                                         {
                                           if (LogConfig.shouldLogIp(ip))
                                           {
                                             logger.trace("[IP:{}] Added to batch", ip);
                                           }
                                         }
                                       }

                                       // Build fping command with all IPs
                                       var command = buildFpingCommand(ipAddresses);
                                       logger.debug("Command: {} (args count={})",
                                                    String.join(" ", command.subList(0, 6)) + " ...", command.size()
                                       );

                                       var processBuilder = new ProcessBuilder(command);
                                       processBuilder.redirectErrorStream(true);  // Merge stderr into stdout

                                       var processStartNs = System.nanoTime();
                                       logger.debug("Starting fping process...");

                                       try
                                       {
                                         var proc = processBuilder.start();
                                         logger.info("Process started: pid={}, timeout={}s",
                                                     proc.pid(), TIMEOUT
                                         );

                                         // CRITICAL FIX: Read output concurrently to prevent buffer deadlock
                                         // With 500+ IPs, fping generates ~50KB output. If stdout buffer fills,
                                         // fping blocks writing and waitFor() blocks forever, causing process reaper
                                         // to spin at high CPU. Solution: Read output asynchronously while process runs.

                                         // Start reading output immediately (non-blocking, uses ForkJoinPool.commonPool)
                                         var outputFuture = CompletableFuture.supplyAsync(() -> {
                                           var readStartNs = System.nanoTime();
                                           var output = readProcessOutput(proc);
                                           var readDurationMs = (System.nanoTime() - readStartNs) / 1_000_000;
                                           logger.debug("Async output read: size={}bytes, duration={}ms, thread={}",
                                                        output.length(), readDurationMs, Thread.currentThread()
                                                          .getName()
                                           );
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
                                           logger.info("Process completed: exitCode={}, duration={}ms",
                                                       exitCode, processDurationMs
                                           );
                                         } catch (InterruptedException e)
                                         {
                                           // Thread interrupted - cleanup
                                           Thread.currentThread()
                                             .interrupt();
                                           proc.destroyForcibly();
                                           outputFuture.cancel(true);
                                           logger.warn("Process INTERRUPTED: duration={}ms",
                                                       (System.nanoTime() - processStartNs) / 1_000_000
                                           );
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                           return new ConcurrentHashMap<>();
                                         } catch (ExecutionException e)
                                         {
                                           // Check if timeout or other failure
                                           if (e.getCause() instanceof TimeoutException)
                                           {
                                             // Timeout occurred - kill process
                                             proc.destroyForcibly();
                                             outputFuture.cancel(true); // Cancel output reading
                                             logger.warn("Process TIMEOUT: duration={}ms, IPs={}, killing process",
                                                         (System.nanoTime() - processStartNs) / 1_000_000, ipAddresses.size()
                                             );
                                           } else
                                           {
                                             // Process failed to start or crashed
                                             logger.error("Process execution failed: {}", e.getCause()
                                               .getMessage()
                                             );
                                           }
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
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
                                           logger.error("Interrupted while reading process output");
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                           return new ConcurrentHashMap<>();
                                         } catch (TimeoutException e)
                                         {
                                           logger.error("Timeout reading process output after 1s (process exited but output not ready)");
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                           return new ConcurrentHashMap<>();
                                         } catch (ExecutionException e)
                                         {
                                           logger.error("Failed to read process output: {}", e.getCause()
                                             .getMessage()
                                           );
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                           return new ConcurrentHashMap<>();
                                         }

                                         var readDurationMs = (System.nanoTime() - parseStartNs) / 1_000_000;
                                         logger.debug("Output retrieved: size={}bytes, wait={}ms",
                                                      output.length(), readDurationMs
                                         );

                                         if (output.isBlank())
                                         {
                                           logger.warn("Empty output from fping process");
                                           publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                           return new ConcurrentHashMap<>();
                                         }

                                         // Parse fping output (concurrent parsing with ConcurrentHashMap)
                                         parseStartNs = System.nanoTime();
                                         var results = FpingParser.parse(output);
                                         var parseDurationMs = (System.nanoTime() - parseStartNs) / 1_000_000;

                                         logger.info("Parsing completed: parsed={}/{}, duration={}ms",
                                                     results.size(), ipAddresses.size(), parseDurationMs
                                         );

                                         // Publish events for each IP concurrently using parallel stream
                                         var publishStartNs = System.nanoTime();

                                         results.entrySet()
                                           .parallelStream()
                                           .forEach(entry -> {
                                             publishResult(vertx, entry.getValue(), pollInterval);

                                             // Option C - Per-IP result logging:
                                             if (LogConfig.shouldLogIp(entry.getKey()))
                                             {
                                               var result = entry.getValue();
                                               logger.trace("[IP:{}] Parsed: status={}, loss={}%, rtt={}ms",
                                                            entry.getKey(),
                                                            result.getBoolean(PingResultUtil.SUCCESS) ? "UP" : "DOWN",
                                                            result.getInteger(PingResultUtil.PACKET_LOSS),
                                                            result.getBoolean(PingResultUtil.SUCCESS) ? result.getDouble(PingResultUtil.AVG_RTT) : -1
                                               );
                                             }
                                           })
                                         ;

                                         var publishDurationMs = (System.nanoTime() - publishStartNs) / 1_000_000;
                                         logger.debug("Publishing completed: events={}, duration={}ms",
                                                      results.size(), publishDurationMs
                                         );

                                         // Handle any IPs that weren't in the parsed results
                                         ipAddresses.stream()
                                           .filter(ip -> !results.containsKey(ip))
                                           .forEach(ip -> {
                                             logger.warn("[IP:{}] Missing from results, publishing ERROR", ip);
                                             publishMissingIp(vertx, ip, pollInterval);
                                           })
                                         ;

                                         return results;

                                       } catch (IOException ioException)
                                       {
                                         logger.error("Failed to start process: {}", ioException.getMessage(), ioException);
                                         publishBatchTimeout(vertx, ipAddresses, pollInterval);
                                         return new ConcurrentHashMap<>();
                                       }
                                     }, false
    );  // ordered=false for better parallelism
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
      logger.error(ERROR_READING_FPING_OUTPUT);
      //      ioException.printStackTrace();
    }

    return output.toString();
  }

  /**
   * Publish event for a single ping result.
   * Thread-safe - can be called concurrently from parallel stream.
   */
  private static void publishResult(Vertx vertx, JsonObject result, int pollInterval)
  {
    var json = new JsonObject().put(FILE_NAME, result.getString(PingResultUtil.IP))
      .put(DATA, PingResultUtil.toCsvRow(result))  // CSV format for new output
      .put(EXIT_CODE, result.getBoolean(PingResultUtil.SUCCESS) ? 0 : 1)
      .put(POLL_INTERVAL, pollInterval)
      ;

    vertx.eventBus()
      .publish(result.getBoolean(PingResultUtil.SUCCESS) ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
  }

  /**
   * Publish timeout failure for all IPs in a batch.
   * Thread-safe - uses parallel stream.
   */
  private static void publishBatchTimeout(Vertx vertx, Set<String> ipAddresses, int pollInterval)
  {
    logger.debug("Publishing timeout for {} IPs", ipAddresses.size());
    ipAddresses.parallelStream()
      .forEach(ip -> {
        if (LogConfig.shouldLogIp(ip))
        {
          logger.trace("[IP:{}] Publishing TIMEOUT event", ip);
        }
        vertx.eventBus()
          .publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip)
            .put(DATA, ip + TIMEOUT_100)
            .put(EXIT_CODE, -1)
            .put(POLL_INTERVAL, pollInterval)
          );
      });
  }

  /**
   * Publish failure for IP that was missing from fping results.
   * Thread-safe.
   */
  private static void publishMissingIp(Vertx vertx, String ip, int pollInterval)
  {
    vertx.eventBus()
      .publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip)
        .put(DATA, ip + ERROR_100)
        .put(EXIT_CODE, -1)
        .put(POLL_INTERVAL, pollInterval)
      );
  }

}
