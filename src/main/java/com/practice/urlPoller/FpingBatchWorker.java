package com.practice.urlPoller;

import static com.practice.urlPoller.Constants.JsonFields.DATA;
import static com.practice.urlPoller.Constants.JsonFields.EXIT_CODE;
import static com.practice.urlPoller.Constants.JsonFields.FILE_NAME;
import static com.practice.urlPoller.Constants.JsonFields.POLL_INTERVAL;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Batch ping executor using fping for high-performance concurrent ping operations.
 * Executes multiple IPs in a single fping process, dramatically reducing thread count.
 * <p>
 * Thread-safe implementation using ConcurrentHashMap and CompletableFuture.
 * <p>
 * Performance: 1000 IPs in 10 interval groups = ~20 threads (vs 2000 with individual ping)
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
  // Custom thread factory for naming batch handler threads
  // private static final ThreadFactory BATCH_HANDLER_THREAD_FACTORY = new ThreadFactory()
  // {
  //   private final AtomicInteger threadNumber = new AtomicInteger(1);
  //
  //   @Override
  //   public Thread newThread(Runnable r)
  //   {
  //     Thread t = new Thread(r);
  //     t.setName("fping-batch-handler-" + threadNumber.getAndIncrement());
  //     t.setDaemon(true);
  //     return t;
  //   }
  // };
  // Shared executor for async process handling with named threads
  private static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Execute fping for a batch of IP addresses concurrently.
   * Thread-safe - uses ConcurrentHashMap internally.
   *
   * @param vertx        Vert.x instance for event bus
   * @param ipAddresses  Set of IPs to ping (can be 100s or 1000s)
   * @param pollInterval The polling interval for this batch (for logging)
   * @return CompletableFuture containing map of IP -> PingResult
   */
  public static CompletableFuture<Map<String, PingResult>> work(Vertx vertx, Set<String> ipAddresses, byte pollInterval)
  {
    if (ipAddresses == null || ipAddresses.isEmpty())
    {
      return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
    }

    // Build fping command with all IPs
    List<String> command = buildFpingCommand(ipAddresses);
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);  // Merge stderr into stdout

    try
    {
      Process proc = processBuilder.start();

      // Use Process.onExit() for non-blocking async wait
      return proc.onExit().orTimeout(30, TimeUnit.SECONDS)  // Longer timeout for large batches
          .handleAsync((process, throwable) -> {
            // This runs on our named thread pool
            Thread.currentThread().setName(FPING_BATCH + pollInterval);

            if (throwable != null)
            {
              // Timeout or other error occurred
              proc.destroyForcibly();
              //            System.err.println("Fping batch timed out for interval " + pollInterval + "s with " + ipAddresses.size() + " IPs");
              System.err.printf(FPING_BATCH_TIMED_OUT_FOR_INTERVAL_WITH_IPS, pollInterval, ipAddresses.size());

              // Publish failure events for all IPs in batch
              publishBatchTimeout(vertx, ipAddresses, pollInterval);
              return new ConcurrentHashMap<>();
            }

            // Process completed - read output
            String output = readProcessOutput(process);

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
            results.entrySet().parallelStream().forEach(entry -> publishResult(vertx, entry.getValue(), pollInterval));

            // Handle any IPs that weren't in the parsed results
            ipAddresses.stream().filter(ip -> !results.containsKey(ip)).forEach(ip -> {
              System.err.println(IP_NOT_IN_FPING_RESULTS + ip);
              publishMissingIp(vertx, ip, pollInterval);
            });

            return results;
          }, ASYNC_EXECUTOR);

    } catch (IOException ioException)
    {
      System.err.println(FAILED_TO_START_FPING_PROCESS);
      ioException.printStackTrace();

      // Publish failures for all IPs
      publishBatchTimeout(vertx, ipAddresses, pollInterval);
      return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
    }
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
        output.append(line).append('\n');
      }
    } catch (IOException ioException)
    {
      System.err.println(ERROR_READING_FPING_OUTPUT);
      ioException.printStackTrace();
    }

    return output.toString();
  }

  /**
   * Publish event for a single ping result.
   * Thread-safe - can be called concurrently from parallel stream.
   */
  private static void publishResult(Vertx vertx, PingResult result, byte pollInterval)
  {
    var json = new JsonObject().put(FILE_NAME, result.getIp()).put(DATA, result.toCsvRow())  // CSV format for new output
        .put(EXIT_CODE, result.isAlive() ? 0 : 1).put(POLL_INTERVAL, pollInterval);

    vertx.eventBus().publish(result.isAlive() ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
  }

  /**
   * Publish timeout failure for all IPs in a batch.
   * Thread-safe - uses parallel stream.
   */
  private static void publishBatchTimeout(Vertx vertx, Set<String> ipAddresses, byte pollInterval)
  {
    ipAddresses.parallelStream().forEach(ip -> vertx.eventBus().publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip).put(DATA, ip + TIMEOUT_100).put(EXIT_CODE, -1).put(POLL_INTERVAL, pollInterval)
    ));
  }

  /**
   * Publish failure for IP that was missing from fping results.
   * Thread-safe.
   */
  private static void publishMissingIp(Vertx vertx, String ip, byte pollInterval)
  {
    vertx.eventBus().publish(PROCESS_FAILED, new JsonObject().put(FILE_NAME, ip).put(DATA, ip + ERROR_100).put(EXIT_CODE, -1).put(POLL_INTERVAL, pollInterval));
  }
}
