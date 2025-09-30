package com.practice.urlPoller;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;

public class WritingVerticle extends VerticleBase
{
  private static FileSystem file_system;

  @Override
  public Future<?> start() throws IOException
  {

    var event_handler = new EventHandler(vertx);

    event_handler.consume(Event.PROCESS_FAILED, message -> {
      var json = message.body();
      var command = json.getString("command");
      var exit_code = json.getString("exit-code");
      var msg = json.getString("data");

      var notification = "Process Failed\nCOMMAND:   %s\nEXIT_CODE: %s\nMSG:       %s"
                                                                                      .formatted(command,
                                                                                                 exit_code,
                                                                                                 msg);

      var file_name = json.getString("file_name");
      writeWithTimeStamps(file_name, notification);
      System.out.println(notification);

    }
    );
    event_handler.consume(Event.PROCESS_SUCCEEDED, message -> {
      var json = message.body();

      var file_name = json.getString("file-name");
      var file_contents = json.getString("data");

      writeWithTimeStamps(file_name, file_contents);
    }
    );

    return Future.succeededFuture();
  }

  private void writeWithTimeStamps(String file_name, String file_contents)
  {
    file_system = vertx.fileSystem();

    var options = new OpenOptions()
                                   .setAppend(true)
                                   .setCreate(true);


    var open_file = file_system.open("stats/" + file_name, options);

    open_file.onFailure(Throwable::printStackTrace).onSuccess(file -> {
      var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      var timestamp = System.currentTimeMillis() + "||" + LocalDateTime.now().format(formatter) + "\n";

      var format = "TIME: %s[\n%s]\n\n".formatted(timestamp, file_contents);
      file.write(Buffer.buffer(format));

    });
  }


}
