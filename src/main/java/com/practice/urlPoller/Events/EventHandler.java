package com.practice.urlPoller.Events;


import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

public class EventHandler
{
  private final Vertx vertx;

  public EventHandler(Vertx vertx)
  {
    this.vertx = vertx;
  }


  /**
   * @return EventBus for fluent interface
   */
  public EventBus publish(Event event, JsonObject message)
  {
    System.out.printf("Event Fired %n`%s`%n`%s`%n", event.toString(), message);
    return vertx.eventBus().publish(event.toString(), message != null ? message : "");
  }

  public MessageConsumer<JsonObject> consume(Event event, Handler<Message<JsonObject>> handler)
  {
    return vertx.eventBus().consumer(event.toString(), handler);
  }

}
