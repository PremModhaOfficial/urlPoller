package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

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
    System.out.println(processBuilder.command());

    var proc = processBuilder.start();
    var exit = proc.waitFor();

    // Read stdout
    try (var reader = proc.inputReader())
    {
      String line;
      while ((line = reader.readLine()) != null)
      {
        System.out.println("stdout: " + line);
      }
    }

    // Read stderr
    try (var errReader = proc.errorReader())
    {
      String errLine;
      while ((errLine = errReader.readLine()) != null)
      {
        System.out.println("stderr: " + errLine);
      }
    }

    System.out.println("Process exited with code: " + exit);

    return Future.succeededFuture();
  }

}

