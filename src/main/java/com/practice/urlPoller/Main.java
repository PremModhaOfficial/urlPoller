package com.practice.urlPoller;

import static com.practice.urlPoller.Constants.JsonFields.DATA;
import static com.practice.urlPoller.Events.Event.CONFIG_LOADED;

import java.util.ArrayList;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;

public class Main
{
  static String PATH = "urls.txt";


  public static void main(String[] args)
  {
    PATH = args.length > 0 ? args[0] : PATH;

    // Configure Vert.x with custom thread naming
    var vertxOptions = new VertxOptions().setInternalBlockingPoolSize(2).setEventLoopPoolSize(2).setWorkerPoolSize(10); // Configure event loop threads

    var vertx = Vertx.vertx(vertxOptions);

    var verticalList = new ArrayList<Future<String>>();
    var fs = vertx.fileSystem();

    verticalList.add(vertx.deployVerticle(new Distributor()));
    verticalList.add(vertx.deployVerticle(new FileWriter()));

    Future.all(verticalList).onFailure(Throwable::printStackTrace).onSuccess(result -> {
      System.out.println("All verticles deployed successfully");

      fs.readFile(PATH).onComplete(buffer -> {
        var json = new JsonObject().put(DATA, buffer.result().toString());
        vertx.eventBus().publish(CONFIG_LOADED, json);

      }).onFailure(Throwable::printStackTrace);
    });
  }
}
