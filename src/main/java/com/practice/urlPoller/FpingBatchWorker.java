package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
public class FpingBatchWorker
{
  public static final String FPING = "fping";
  public static final String COUNT_FLAG = "-c";
  public static final String COUNT_ICMP = "3";
  public static final String TIMEOUT_FLAG = "-t";
  public static final String TIMEOUT_MILS = "2000";
  public static final String QUIET_MODE_FLAG = "-q";
  public static final String FPING_BATCH = "fping-batch-";
  public static final String FPING_BATCH_TIMED_OUT_FOR_INTERVAL_WITH_IPS = "Fping batch timed out for interval %ss with  %s IPs\n";
  public static final String EMPTY_OUTPUT_FROM_FPING_BATCH_FOR_INTERVAL = "Empty output from fping batch for interval ";
  public static final String FPING_BATCH_COMPLETED_IPS_PARSED_FOR_INTERVAL = "Fping batch completed: %s/%s IPs parsed for interval %ss\n";
  public static final String IP_NOT_IN_FPING_RESULTS = "IP not in fping results: ";
  public static final String FAILED_TO_START_FPING_PROCESS = "Failed to start fping process";
  public static final String ERROR_READING_FPING_OUTPUT = "Error reading fping output";
  public static final String TIMEOUT_100 = ",TIMEOUT,100%,-,-,-";
  public static final String ERROR_100 = ",ERROR,100%,-,-,-";

  /**
   * Execute fping for a batch of IP addresses using Vert.x WorkerExecutor.
   * Thread-safe - uses ConcurrentHashMap and Vert.x worker pool.
   *
   * @param vertx        Vert.x instance for event bus
   * @param ipAddresses  Set of IPs to ping (can be 100s or 1000s)
   * @param pollInterval The polling interval for this batch (for logging)
   * @return Future containing map of IP -> PingResult
   */
  public static Future<Map<String, PingResult>> work(Vertx vertx, Set<String> ipAddresses, Integer pollInterval)
  {
    if (ipAddresses == null || ipAddresses.isEmpty())
    {
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    WorkerExecutor fpingPool = Main.getFpingWorkerPool();
    if (fpingPool == null)
    {
      System.err.println("Worker pool not initialized");
      publishBatchTimeout(vertx, ipAddresses, pollInterval);
      return Future.succeededFuture(new ConcurrentHashMap<>());
    }

    // Execute blocking fping operation on worker pool using Callable
    return fpingPool.executeBlocking(() -> {
      // This executes on one of the 6 worker threads
      Thread.currentThread()
            .setName(FPING_BATCH + pollInterval);

      // Build fping command with all IPs
      List<String> command = buildFpingCommand(ipAddresses);
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);  // Merge stderr into stdout

      try
      {
        Process proc = processBuilder.start();

        // Wait for process completion with timeout
        boolean completed = proc.waitFor(6, TimeUnit.SECONDS);

        if (!completed)
        {
          // Timeout occurred
          proc.destroyForcibly();
          System.err.printf(FPING_BATCH_TIMED_OUT_FOR_INTERVAL_WITH_IPS, pollInterval, ipAddresses.size());
          publishBatchTimeout(vertx, ipAddresses, pollInterval);
          return new ConcurrentHashMap<>();
        }

        // Process completed - read output
        String output = readProcessOutput(proc);

        if (output.isBlank())
        {
          System.err.println(EMPTY_OUTPUT_FROM_FPING_BATCH_FOR_INTERVAL + pollInterval);
          publishBatchTimeout(vertx, ipAddresses, pollInterval);
          return new ConcurrentHashMap<>();
        }

        // Parse fping output (concurrent parsing with ConcurrentHashMap)
        Map<String, PingResult> results = FpingResultParser.parse(output);

        System.out.printf(FPING_BATCH_COMPLETED_IPS_PARSED_FOR_INTERVAL, results.size(), ipAddresses.size(), pollInterval);

        // Publish events for each IP concurrently using parallel stream
        results.entrySet()
               .parallelStream()
               .forEach(entry -> publishResult(vertx, entry.getValue(), pollInterval))
        ;

        // Handle any IPs that weren't in the parsed results
        ipAddresses.stream()
                   .filter(ip -> !results.containsKey(ip))
                   .forEach(ip -> {
                     System.err.println(IP_NOT_IN_FPING_RESULTS + ip);
                     publishMissingIp(vertx, ip, pollInterval);
                   })
        ;

        return results;

      } catch (IOException ioException)
      {
        System.err.println(FAILED_TO_START_FPING_PROCESS);
//        ioException.printStackTrace();
        publishBatchTimeout(vertx, ipAddresses, pollInterval);
        return new ConcurrentHashMap<>();
      } catch (InterruptedException interruptedException)
      {
        System.err.println("Fping process interrupted");
//        interruptedException.printStackTrace();
        publishBatchTimeout(vertx, ipAddresses, pollInterval);
        Thread.currentThread()
              .interrupt();
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
    StringBuilder output = new StringBuilder(2048);

    try (BufferedReader reader = new BufferedReader(process.inputReader(), 8192))
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
  private static void publishResult(Vertx vertx, PingResult result, int pollInterval)
  {
    var json = new JsonObject().put(FILE_NAME, result.getIp())
                               .put(DATA, result.toCsvRow())  // CSV format for new output
                               .put(EXIT_CODE, result.isSuccess() ? 0 : 1)
                               .put(POLL_INTERVAL, pollInterval)
      ;

    vertx.eventBus()
         .publish(result.isSuccess() ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
  }

  /**
   * Publish timeout failure for all IPs in a batch.
   * Thread-safe - uses parallel stream.
   */
  private static void publishBatchTimeout(Vertx vertx, Set<String> ipAddresses, int pollInterval)
  {
    ipAddresses.parallelStream()
               .forEach(ip -> vertx.eventBus()
                                   .publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip)
                                                                            .put(DATA, ip + TIMEOUT_100)
                                                                            .put(EXIT_CODE, -1)
                                                                            .put(POLL_INTERVAL, pollInterval)
                                   ));
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
                                                  .put(POLL_INTERVAL, pollInterval));
  }

  public static Future<Map<String, PingResult>> work(Vertx vertx, Map<Integer, Set<String>> gcdGroup, int timer, int tick)
  {
    System.out.println();
    System.out.println();
    System.out.println("FpingBatchWorker.work///");
    var candidateIds = gcdGroup.keySet()
                               .stream()
                               .map(Key -> Key % tick == 0 ? Key : -1)
                               .collect(Collectors.toSet())
      ;

    candidateIds.remove(-1);

    var candidateSet = new HashSet<String>();

    for (var candidate : candidateIds)
    {
      var kk = gcdGroup.get(candidate);
      candidateSet.addAll(kk);
    }


    System.out.println("tick = " + tick);
    System.out.println("timer = " + timer);
    System.out.println("candidateSet = " + candidateSet);

    System.out.println("FpingBatchWorker.work///");
    System.out.println();
    System.out.println();
    return work(vertx, candidateSet, timer);
  }
}
