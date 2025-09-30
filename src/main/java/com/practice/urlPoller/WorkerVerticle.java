package com.practice.urlPoller;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

// executes for a single ip Address
public class WorkerVerticle extends VerticleBase
{

  private ProcessBuilder processBuilder;

  public WorkerVerticle(ProcessBuilder processBuilder)
  {
    this.processBuilder = processBuilder;
  }

  @Override
  public Future<?> start() throws Exception
  {
    // System.out.println(processBuilder.command());

    final var event_hander = new EventHandler(vertx);
    var process_buffer = new StringBuffer();

    var file_name = processBuilder.command().get(3);

    var proc = processBuilder.start();
    var exit = proc.waitFor();

    // Read stdout
    try (var reader = proc.inputReader())
    {
      String line;
      process_buffer = new StringBuffer();
      while ((line = reader.readLine()) != null)
      {
        process_buffer.append(line + "\n");
        // System.out.println("stdout: " + line);
      }
      if (exit == 0)
      {
        var json = new JsonObject().put("data", process_buffer.toString())
                                   .put("file-name", file_name)
                                   .put("command", processBuilder.command().toString())
                                   .put("exit-code", exit);
        event_hander.publish(Event.PROCESS_SUCCEEDED, json);
      }
      else
      {
        var json = new JsonObject().put("data", process_buffer.toString())
                                   .put("file-name", file_name)
                                   .put("command", processBuilder.command().toString())
                                   .put("exit-code", exit);
        event_hander.publish(Event.PROCESS_FAILED, json);
      }
    }

    // INFO: did not execute
    // Read stderr
    //
    // try (var errReader = proc.errorReader())
    // {
    //   String errLine;
    //   process_buffer = new StringBuffer();
    //   while ((errLine = errReader.readLine()) != null)
    //   {
    //     process_buffer.append(errLine);
    //     System.out.println("stderr: " + errLine);
    //   }
    // }
    // System.out.println("Process exited with code: " + exit);

    return Future.succeededFuture();
  }

}
