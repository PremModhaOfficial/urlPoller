package com.practice.urlPoller;

import java.io.IOException;
import java.util.ArrayList;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

public class MainVerticle extends VerticleBase {
  static String PATH = "urls.txt";

  public static void main(String[] args) {

    if (args.length > 0) {
      PATH = args[0];
    }
    var vertx = Vertx.vertx();
    vertx.deployVerticle(MainVerticle.class.getName());
  }

  @Override
  public Future<?> start() throws IOException {

    var vertical_list = new ArrayList<Future<String>>();

    vertical_list.add(vertx.deployVerticle(new ConfigReaderVerticle(PATH)));
    vertical_list.add(vertx.deployVerticle(new TaskSchedulerVerticle()));
    vertical_list.add(vertx.deployVerticle(new WritingVerticle()));

    Future.all(vertical_list)
        .onFailure(Throwable::printStackTrace);

    return Future.succeededFuture();

  }
}
