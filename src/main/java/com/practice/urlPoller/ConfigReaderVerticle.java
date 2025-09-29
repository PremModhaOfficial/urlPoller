package com.practice.urlPoller;


import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

public class ConfigReaderVerticle extends VerticleBase
{

  private String file_path;

  public ConfigReaderVerticle(String file_path)
  {
    this.file_path = file_path;
  }

  @Override
  public Future<?> start() throws Exception
  {
    var event_handler = new EventHandler(vertx);
    // read file
    var fs = vertx.fileSystem();


    var data = Buffer.buffer();

    fs.readFile(file_path)
      .onComplete(buffer -> data.appendBuffer(buffer.result()))
      .onFailure(Throwable::printStackTrace);

    // System.out.println(data.toString());
    //
    var json = new JsonObject().put("data", data.toString());
    event_handler.publish(Event.CONFIG_LOADED, json);


    return Future.succeededFuture();

    // split by new line
    // and add them to key value store
    // then fire event thath the config is loaded
  }
}

