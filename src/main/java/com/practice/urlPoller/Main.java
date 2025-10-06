package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Main
{
  public static final String FPING_WORKER = "fping-worker";
  static String PATH = "urls.txt";
  private static WorkerExecutor fpingWorkerPool;

  /**
   * Get the shared fping worker pool.
   * Thread-safe - can be called from any verticle.
   */
  public static WorkerExecutor getFpingWorkerPool()
  {
    return fpingWorkerPool;
  }

  public static void main(String[] args)
  {
    PATH = args.length > 0 ? args[0] : PATH;

    // Configure Vert.x with minimal thread pools
    // Event loops: 2 (sufficient for event-driven processing)
    // Default worker pool: 0 (not used - we use named pool)
    // Internal blocking pool: 2 (for file I/O)
    var vertxOptions = new VertxOptions().setEventLoopPoolSize(2)
                                         .setWorkerPoolSize(1)
                                         .setInternalBlockingPoolSize(2)
      ;

    var vertx = Vertx.vertx(vertxOptions);

    // Create dedicated worker pool for fping batch processing
    // Pool size: 6 threads (optimal for ~11 intervals with staggered execution)
    // Max execute time: 10 seconds (fping timeout is 6s + buffer)
    fpingWorkerPool = vertx.createSharedWorkerExecutor(FPING_WORKER);

    var verticalList = new ArrayList<Future<String>>();
    var fs = vertx.fileSystem();

    verticalList.add(vertx.deployVerticle(new Distributor()));
    verticalList.add(vertx.deployVerticle(new FileWriter()));

    Future.all(verticalList)
          .onFailure(Throwable::printStackTrace)
          .onSuccess(result -> {
            System.out.println("All verticles deployed successfully");

            fs.readFile(PATH)
              .onComplete(buffer -> {
                var json = new JsonObject().put(DATA, buffer.result()
                                                            .toString());
                vertx.eventBus()
                     .publish(CONFIG_LOADED, json);

              })
              .onFailure(Throwable::printStackTrace)
            ;
          })
    ;

    // Cleanup on shutdown
    Runtime.getRuntime()
           .addShutdownHook(new Thread(() -> {
             if (fpingWorkerPool != null)
             {
               fpingWorkerPool.close();
             }
             vertx.close();
           }));
  }
}
