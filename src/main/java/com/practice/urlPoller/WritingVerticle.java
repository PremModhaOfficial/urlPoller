package com.practice.urlPoller;

import static com.practice.urlPoller.Constanst.JsonFilds.COMMAND;
import static com.practice.urlPoller.Constanst.JsonFilds.DATA;
import static com.practice.urlPoller.Constanst.JsonFilds.EXIT_CODE;
import static com.practice.urlPoller.Constanst.JsonFilds.FILE_NAME;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.practice.urlPoller.Events.Event;
import com.practice.urlPoller.Events.EventHandler;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;

public class WritingVerticle extends VerticleBase
{
  private static final String SEPARATOR = "||";
  private static final String FILE_PARENT = "stats/";
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
  private static final String PROGRESS_UNIT_FORMAT = "TIME: %s[\n%s]\n\n";

  static
  {
    new File(FILE_PARENT).mkdir();
  }

  @Override
  public Future<?> start() throws IOException
  {

    var eventHandler = new EventHandler(vertx);

    eventHandler.consume(Event.PROCESS_FAILED, message -> {
      var json = message.body();
      var notification = "Process Failed\nCOMMAND:   %s\nEXIT_CODE: %s\nMSG:       %s".formatted(json.getString(COMMAND), json.getString(EXIT_CODE), json.getString(DATA));

      var fileName = json.getString(FILE_NAME);
      writeWithTimeStamps(fileName, notification);
      System.out.println(notification);

    }
    );
    eventHandler.consume(Event.PROCESS_SUCCEEDED, message -> {
      var json = message.body();

      var fileName = json.getString(FILE_NAME);
      var fileContents = json.getString(DATA);

      writeWithTimeStamps(fileName, fileContents);
    }
    );

    return Future.succeededFuture();
  }

  private void writeWithTimeStamps(String fileName, String fileContents)
  {


    vertx.fileSystem().open(FILE_PARENT + fileName, new OpenOptions().setAppend(true).setCreate(true)).onFailure(Throwable::printStackTrace).onSuccess(file -> {
      var format = PROGRESS_UNIT_FORMAT.formatted(
          System.currentTimeMillis() + SEPARATOR + LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)) + "\n", fileContents);
      file.write(Buffer.buffer(format));

    });
  }


}
