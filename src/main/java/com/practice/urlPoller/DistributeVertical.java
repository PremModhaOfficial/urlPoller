package com.practice.urlPoller;


import static com.practice.urlPoller.Constanst.JsonFilds.DATA;
import static com.practice.urlPoller.Events.Event.TIMER_EXPIRED;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

public class DistributeVertical extends VerticleBase
{

  public static final String LINE_BREAK = "\n";
  private static Map<Byte, Set<Ip>> ipTable;

  @Override
  public Future<?> start()
  {
    var eventHandler = new EventHandler(vertx);
    eventHandler.consume(TIMER_EXPIRED, message -> {
      var timer = Byte.valueOf(message.body().getString(DATA));

      System.out.println("timer = " + timer);
      var set = ipTable.get(timer);

      var executor = vertx.createSharedWorkerExecutor("Motadata", 5);
      Future.all(
                 set.stream()
                    .map(ip -> executor.executeBlocking(() -> Worker.work(vertx,
                                                                          new ProcessBuilder("ping",
                                                                                             "-c",
                                                                                             "1",
                                                                                             ip.getAddress()))
                    ))
                    .toList()

      )
            .onFailure(Throwable::printStackTrace);


      vertx.setTimer((timer * 1000), i -> {
        eventHandler.publish(TIMER_EXPIRED, message.body());
      });
    });

    eventHandler.consume(Event.CONFIG_LOADED, json -> {
      var data = json.body().getString(DATA);
      var urls = data.split(LINE_BREAK);
      ipTable = new HashMap<>();

      for (var url : urls)
      {
        var ip = new Ip(url);

        byte pollTime = ip.getPollTime();
        if (!ipTable.containsKey(pollTime)) ipTable.put(pollTime, new HashSet<>());

        ipTable.get(pollTime).add(ip);
      }


      for (var ent : ipTable.entrySet())
      {
        eventHandler.publish(TIMER_EXPIRED, new JsonObject().put(DATA, ent.getKey()));
      }

    });


    return Future.succeededFuture();
  }

}

