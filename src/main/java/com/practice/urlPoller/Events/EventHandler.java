package com.practice.urlPoller.Events;


import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

public class EventHandler
{
  private final Vertx vertx;

  public EventHandler(Vertx vertx)
  {
    this.vertx = vertx;
  }

  public void publish(Event event, JsonObject message)
  {
    // System.out.printf("EVENT: `%s` `%s` %n", event.toString(), message);
    // System.out.printf("EVENT: `%s` %n", event.toString()/* , message */);
    vertx.eventBus().publish(event.toString(), message != null ? message : "");
  }

  public void consume(Event event, Handler<Message<JsonObject>> handler)
  {
    vertx.eventBus().consumer(event.toString(), handler);
  }

}
