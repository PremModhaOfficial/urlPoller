package com.practice.urlPoller;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;

import java.io.IOException;

public class WritingVerticle extends VerticleBase
{
  private static FileSystem file_system;

  @Override
  public Future<?> start() throws IOException
  {

    var event_handler = new EventHandler(vertx);

    event_handler.consume(Event.PROCESS_FAILED, message -> {
                            var json = message.body();
                            var keys = json.fieldNames();
                            System.out.println(json.getString("exit-code"));
                          }
    );
    event_handler.consume(Event.PROCESS_FINISHED_WITH_SUCCESS, message -> {
                            var json = message.body();
                            var file_name = json.getString("file-name");
                            var file_contents = json.getString("data");

                            writeToFile(file_name, file_contents);
                          }
    );

    return Future.succeededFuture();
  }

  private void writeToFile(String file_name, String file_contents)
  {
    file_system = vertx.fileSystem();

    var options = new OpenOptions()
      .setAppend(true)
      .setCreate(true);


    var open_file = file_system.open("stats/"+file_name, options);

    open_file.onFailure(Throwable::printStackTrace).onSuccess(file -> {
      file.write(Buffer.buffer(file_contents));
    });
  }


}
