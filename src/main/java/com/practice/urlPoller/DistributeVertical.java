package com.practice.urlPoller;


import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.practice.urlPoller.Constanst.JsonFilds.DATA;

public class DistributeVertical extends VerticleBase
{

  public static final String LINE_BREAK = "\n";
  private static Map<Byte, Set<Ip>> ipTable;

  @Override
  public Future<?> start()
  {
    var eventHandler = new EventHandler(vertx);

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
        //        System.out.println(ent);
        vertx.setPeriodic(
          0,
          ent.getKey() * 1000,
          (id) -> vertx.deployVerticle(new PingVerticle(ent.getValue())));
      }

    });


    return Future.succeededFuture();
  }

}

