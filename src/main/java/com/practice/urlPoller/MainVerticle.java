package com.practice.urlPoller;

import static com.practice.urlPoller.Constanst.JsonFilds.DATA;

import java.util.ArrayList;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

public class MainVerticle extends VerticleBase
{
  static String PATH = "urls.txt";

  public static void main(String[] args)
  {
    if (args.length > 0)
    {
      PATH = args[0];
    }
    var vertx = Vertx.vertx();
    vertx.deployVerticle(MainVerticle.class.getName()).onFailure(Throwable::printStackTrace);
  }

  @Override
  public Future<?> start()
  {

    var verticalList = new ArrayList<Future<String>>();
    var eventHandler = new EventHandler(vertx);
    // read file
    var fs = vertx.fileSystem();

    var data = Buffer.buffer();

    verticalList.add(vertx.deployVerticle(new DistributeVertical()));
    verticalList.add(vertx.deployVerticle(new WritingVerticle()));
    Future.all(verticalList).onFailure(Throwable::printStackTrace);

    fs.readFile(PATH).onComplete(buffer -> {
      data.appendBuffer(buffer.result());
      var json = new JsonObject().put(DATA, data.toString());
      eventHandler.publish(Event.CONFIG_LOADED, json);

    }).onFailure(Throwable::printStackTrace);
    return Future.succeededFuture();

  }
}
