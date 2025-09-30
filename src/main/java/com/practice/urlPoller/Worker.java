package com.practice.urlPoller;

import static com.practice.urlPoller.Constanst.JsonFilds.COMMAND;
import static com.practice.urlPoller.Constanst.JsonFilds.DATA;
import static com.practice.urlPoller.Constanst.JsonFilds.EXIT_CODE;
import static com.practice.urlPoller.Constanst.JsonFilds.FILE_NAME;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

// executes for a single ip Address
public class Worker
{

  public static boolean work(Vertx vertx, ProcessBuilder processBuilder)
  {
    System.out.println("Worker.work");

    try
    {
      final var eventHandler = new EventHandler(vertx);
      var processBuffer = new StringBuilder();

      // Updated index from 3 to 5 due to additional ping arguments (-w and timeout value)
      var fileName = processBuilder.command().get(5);


      var proc = processBuilder.start();
      
      // Wait for process with 5 second timeout
      boolean completed = proc.waitFor(5, TimeUnit.SECONDS);
      
      if (!completed)
      {
        // Process didn't complete in time, destroy it
        proc.destroy();
        System.err.println("Process timed out for: " + fileName);
        
        var json = new JsonObject()
          .put(DATA, "Process timed out after 5 seconds")
          .put(FILE_NAME, fileName)
          .put(COMMAND, processBuilder.command().toString())
          .put(EXIT_CODE, -1);
        eventHandler.publish(PROCESS_FAILED, json);
        return false;
      }
      
      var exit = proc.exitValue();

      // Read stdout
      try (var reader = proc.inputReader())
      {
        String line;
        processBuffer = new StringBuilder();
        while ((line = reader.readLine()) != null)
        {
          processBuffer.append(line).append("\n");
          // System.out.println("stdout: " + line);
        }
        var json = new JsonObject().put(DATA, processBuffer.toString()).put(FILE_NAME, fileName).put(COMMAND, processBuilder.command().toString()).put(EXIT_CODE, exit);
        eventHandler.publish(exit == 0 ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
      }
    } catch (IOException | InterruptedException ioException)
    {
      ioException.printStackTrace();
      return false;
    }

    return true;

  }

}
