package com.practice.urlPoller;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

public class TaskSchedulerVerticle extends VerticleBase
{

  private static Map<Byte, Set<Ip>> ip_table;

  @Override
  public Future<?> start() throws Exception
  {
    var event_handeler = new EventHandler(vertx);

    event_handeler.consume(Event.CONFIG_LOADED, json -> {
      var data = json.body().getString("data");
      var urls = data.split("\n");
      ip_table = new HashMap<>();

      for (var url : urls)
      {
        var ip = new Ip(url);

        byte pollTime = ip.getPollTime();
        if (!ip_table.containsKey(pollTime))
        {
          // first entry
          ip_table.put(pollTime, new HashSet<>());

        }
        ip_table.get(pollTime).add(ip);

      }

      for (var ent : ip_table.entrySet())
      {
        System.out.println(ent);
        vertx.setPeriodic(0, ent.getKey() * 1000, (id) -> {
          var ip_set = ent.getValue();
          vertx.deployVerticle(new PingVerticle(ip_set));
        });
      }


      // System.out.println(url_que);

    });


    return Future.succeededFuture();
  }

}

