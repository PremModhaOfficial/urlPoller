package com.practice.urlPoller;

import static com.practice.urlPoller.Constanst.JsonFilds.COMMAND;
import static com.practice.urlPoller.Constanst.JsonFilds.DATA;
import static com.practice.urlPoller.Constanst.JsonFilds.EXIT_CODE;
import static com.practice.urlPoller.Constanst.JsonFilds.FILE_NAME;
import static com.practice.urlPoller.Events.Event.PROCESS_FAILED;
import static com.practice.urlPoller.Events.Event.PROCESS_SUCCEEDED;

import java.io.IOException;

import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

// executes for a single ip Address
public class Worker
{

  public static boolean work(Vertx vertx, ProcessBuilder processBuilder)
  {

    try
    {
      final var eventHandler = new EventHandler(vertx);
      var processBuffer = new StringBuilder();

      var fileName = processBuilder.command().get(3);


      var proc = processBuilder.start();
      var exit = proc.waitFor();

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
        var json = new JsonObject().put(DATA, processBuffer.toString())
                                   .put(FILE_NAME, fileName)
                                   .put(COMMAND, processBuilder.command().toString())
                                   .put(EXIT_CODE, exit);
        eventHandler.publish(exit == 0 ? PROCESS_SUCCEEDED : PROCESS_FAILED, json);
      }
    }
    catch (IOException | InterruptedException ioException)
    {
      ioException.printStackTrace();
      return false;
    }

    return true;

  }

}
