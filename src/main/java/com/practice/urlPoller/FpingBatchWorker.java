package com.practice.urlPoller;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.practice.urlPoller.Constants.JsonFields.*;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

/**
 * Batch ping executor using fping for high-performance concurrent ping operations.
 * Executes multiple IPs in a single fping process, dramatically reducing thread count.
 * 
 * Thread-safe implementation using ConcurrentHashMap and CompletableFuture.
 * 
 * Performance: 1000 IPs in 10 interval groups = ~20 threads (vs 2000 with individual ping)
 */
public class FpingBatchWorker
{
  // Custom thread factory for naming batch handler threads
  private static final ThreadFactory BATCH_HANDLER_THREAD_FACTORY = new ThreadFactory()
  {
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r)
    {
      Thread t = new Thread(r);
      t.setName("fping-batch-handler-" + threadNumber.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  };

  // Shared executor for async process handling with named threads
  private static final ExecutorService ASYNC_EXECUTOR = 
    Executors.newCachedThreadPool(BATCH_HANDLER_THREAD_FACTORY);

  /**
   * Execute fping for a batch of IP addresses concurrently.
   * Thread-safe - uses ConcurrentHashMap internally.
   * 
   * @param vertx Vert.x instance for event bus
   * @param ipAddresses Set of IPs to ping (can be 100s or 1000s)
   * @param pollInterval The polling interval for this batch (for logging)
   * @return CompletableFuture containing map of IP -> PingResult
   */
  public static CompletableFuture<Map<String, PingResult>> work(
    Vertx vertx,
    Set<String> ipAddresses,
    byte pollInterval)
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
      return proc.onExit()
        .orTimeout(30, TimeUnit.SECONDS)  // Longer timeout for large batches
        .handleAsync((process, throwable) -> {
          // This runs on our named thread pool
          Thread.currentThread().setName("fping-batch-" + pollInterval + "s");

          if (throwable != null)
          {
            // Timeout or other error occurred
            proc.destroyForcibly();
            System.err.println("Fping batch timed out for interval " + pollInterval + 
              "s with " + ipAddresses.size() + " IPs");

            // Publish failure events for all IPs in batch
            publishBatchTimeout(vertx, ipAddresses, pollInterval);
            return new ConcurrentHashMap<>();
          }

          // Process completed - read output
          String output = readProcessOutput(process);
          
          if (output.isBlank())
          {
            System.err.println("Empty output from fping batch for interval " + pollInterval);
            publishBatchTimeout(vertx, ipAddresses, pollInterval);
            return new ConcurrentHashMap<>();
          }

          // Parse fping output (concurrent parsing with ConcurrentHashMap)
          Map<String, PingResult> results = FpingResultParser.parse(output);

          System.out.println("Fping batch completed: " + results.size() + "/" + 
            ipAddresses.size() + " IPs parsed for interval " + pollInterval + "s");

          // Publish events for each IP concurrently using parallel stream
          results.entrySet().parallelStream()
            .forEach(entry -> publishResult(vertx, entry.getValue(), pollInterval));

          // Handle any IPs that weren't in the parsed results
          ipAddresses.stream()
            .filter(ip -> !results.containsKey(ip))
            .forEach(ip -> {
              System.err.println("IP not in fping results: " + ip);
              publishMissingIp(vertx, ip, pollInterval);
            });

          return results;
        }, ASYNC_EXECUTOR);

    }
    catch (IOException ioException)
    {
      System.err.println("Failed to start fping process");
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
    List<String> command = new ArrayList<>();
    command.add("fping");
    command.add("-c");
    command.add("1");           // 1 ping per IP
    command.add("-t");
    command.add("2000");        // 2 second timeout per IP
    command.add("-q");          // Quiet mode - only show summary
    command.addAll(ipAddresses); // Add all IPs
    
    return command;
  }

  /**
   * Read all output from process (stdout + stderr merged).
   * Thread-safe.
   */
  private static String readProcessOutput(Process process)
  {
    StringBuilder output = new StringBuilder();
    
    try (BufferedReader reader = process.inputReader())
    {
      String line;
      while ((line = reader.readLine()) != null)
      {
        output.append(line).append("\n");
      }
    }
    catch (IOException e)
    {
      System.err.println("Error reading fping output");
      e.printStackTrace();
    }

    return output.toString();
  }

  /**
   * Publish event for a single ping result.
   * Thread-safe - can be called concurrently from parallel stream.
   */
  private static void publishResult(Vertx vertx, PingResult result, byte pollInterval)
  {
    JsonObject json = new JsonObject()
      .put(FILE_NAME, result.getIp())
      .put(DATA, result.toCsvRow())  // CSV format for new output
      .put("text_format", result.toFormattedText())  // Optional text format
      .put(EXIT_CODE, result.isAlive() ? 0 : 1)
      .put("poll_interval", pollInterval)
      .put("latency_ms", result.getAvgRtt())
      .put("packet_loss", result.getPacketLoss());

    String eventType = result.isAlive() ? PROCESS_SUCCEEDED : PROCESS_FAILED;
    vertx.eventBus().publish(eventType, json);
  }

  /**
   * Publish timeout failure for all IPs in a batch.
   * Thread-safe - uses parallel stream.
   */
  private static void publishBatchTimeout(Vertx vertx, Set<String> ipAddresses, byte pollInterval)
  {
    ipAddresses.parallelStream()
      .forEach(ip -> {
        JsonObject json = new JsonObject()
          .put(FILE_NAME, ip)
          .put(DATA, ip + ",TIMEOUT,100%,-,-,-")
          .put(EXIT_CODE, -1)
          .put("poll_interval", pollInterval);
        
        vertx.eventBus().publish(PROCESS_FAILED, json);
      });
  }

  /**
   * Publish failure for IP that was missing from fping results.
   * Thread-safe.
   */
  private static void publishMissingIp(Vertx vertx, String ip, byte pollInterval)
  {
    JsonObject json = new JsonObject()
      .put(FILE_NAME, ip)
      .put(DATA, ip + ",ERROR,100%,-,-,-")
      .put(EXIT_CODE, -1)
      .put("poll_interval", pollInterval);
    
    vertx.eventBus().publish(PROCESS_FAILED, json);
  }
}
