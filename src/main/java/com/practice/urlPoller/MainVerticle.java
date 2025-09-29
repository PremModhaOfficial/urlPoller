package com.practice.urlPoller;

import java.io.IOException;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

public class MainVerticle extends VerticleBase
{
  static final String PATH = "urls.txt";

  public static void main(String[] args)
  {
    var vertx = Vertx.vertx();
    vertx.deployVerticle(MainVerticle.class.getName());
  }

  @Override
  public Future<?> start() throws IOException
  {


    // TODO: add this through the cmd args
    vertx.deployVerticle(new ConfigReaderVerticle(PATH))
         .onSuccess(a -> Future.succeededFuture())
         .onFailure(Throwable::printStackTrace);

    vertx.deployVerticle(new TaskSchedulerVerticle())
         .onSuccess(a -> Future.succeededFuture())
         .onFailure(Throwable::printStackTrace);


    return Future.succeededFuture();

  }
}
