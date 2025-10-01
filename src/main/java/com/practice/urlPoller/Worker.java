package com.practice.urlPoller;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.practice.urlPoller.Constants.JsonFields.*;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

// executes for a single ip Address
public class Worker
{
  // Custom thread factory for naming process handler threads
  private static final ThreadFactory PROCESS_HANDLER_THREAD_FACTORY = new ThreadFactory()
  {
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    
    @Override
    public Thread newThread(Runnable r)
    {
      Thread t = new Thread(r);
      t.setName("ping-handler-" + threadNumber.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  };
  
  // Shared executor for async process handling with named threads
  private static final java.util.concurrent.ExecutorService ASYNC_EXECUTOR = Executors.newCachedThreadPool(PROCESS_HANDLER_THREAD_FACTORY);

  public static CompletableFuture<Boolean> work(Vertx vertx, ProcessBuilder processBuilder)
  {
    String fileName = processBuilder.command().get(5);

    try
    {
      var proc = processBuilder.start();

      // Use Process.onExit() for non-blocking async wait with custom executor
      return proc.onExit()
        .orTimeout(5, TimeUnit.SECONDS)
        .handleAsync((process, throwable) -> {
          // This runs on our named thread pool
          Thread.currentThread().setName("ping-handler-" + fileName);
          
          if (throwable != null)
          {
            // Timeout or other error occurred
            proc.destroyForcibly();
            System.err.println("Process timed out for: " + fileName + " on thread: " + Thread.currentThread().getName());

            var json = new JsonObject()
              .put(DATA, "Process timed out after 5 seconds")
              .put(FILE_NAME, fileName)
              .put(COMMAND, processBuilder.command().toString())
              .put(EXIT_CODE, -1);
            vertx.eventBus().publish(PROCESS_FAILED, json);
            return false;
          }

          // Process completed successfully within timeout
          var exit = process.exitValue();
          var processBuffer = new StringBuilder();

          // Read stdout
          try (BufferedReader reader = process.inputReader())
          {
            String line;
            while ((line = reader.readLine()) != null)
            {
              processBuffer.append(line).append("\n");
            }
          } catch (IOException e)
          {
            System.err.println("Error reading process output for: " + fileName);
            e.printStackTrace();
          }

          var json = new JsonObject()
            .put(DATA, processBuffer.toString())
            .put(FILE_NAME, fileName)
            .put(COMMAND, processBuilder.command().toString())
            .put(EXIT_CODE, exit);
          vertx.eventBus().publish(exit == 0 ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);

          return true;
        }, ASYNC_EXECUTOR);

    } catch (IOException ioException)
    {
      ioException.printStackTrace();
      return CompletableFuture.completedFuture(false);
    }
  }

}
