package com.practice.urlPoller;

import java.util.ArrayList;
import java.util.Set;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;

public class PingVerticle extends VerticleBase
{
  private Set<Ip> ip_set;

  public PingVerticle(Set<Ip> ip_set)
  {
    this.ip_set = ip_set;
  }

  @Override
  public Future<?> start() throws Exception
  {
    var list_of_commands = new ArrayList<Object>();


    var iter = ip_set.iterator();


    while (iter.hasNext())
    {

      vertx.deployVerticle(new WorkerVerticle(new ProcessBuilder("ping", "-c", "1", iter.next().getAddress())),
                           new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER));

    }


    return Future.succeededFuture();
  }

}

