package com.practice.urlPoller;

import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

public class PingVerticle extends VerticleBase
{
  private final Set<Ip> ipSet;

  public PingVerticle(Set<Ip> ipSet)
  {
    this.ipSet = ipSet;
  }

  @Override
  public Future<?> start()
  {

    var iter = ipSet.iterator();
    var executor = vertx.createSharedWorkerExecutor("Motadata-Executor", 9);

    while (iter.hasNext())
    {

      executor.executeBlocking(() -> Worker.work(vertx, new ProcessBuilder("ping", "-c", "1", iter.next().getAddress())));

    }

    return Future.succeededFuture();
  }

}
