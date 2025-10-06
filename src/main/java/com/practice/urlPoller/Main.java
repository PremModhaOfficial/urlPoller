package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Main
{
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

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
    logger.info("=== URL Poller Starting ===");
    PATH = args.length > 0 ? args[0] : PATH;
    logger.info("Config file: {}", PATH);

    // Configure Vert.x with minimal thread pools
    // Event loops: 2 (sufficient for event-driven processing)
    // Default worker pool: 0 (not used - we use named pool)
    // Internal blocking pool: 2 (for file I/O)
    var vertxOptions = new VertxOptions().setEventLoopPoolSize(2)
                                          .setWorkerPoolSize(5)
                                          .setInternalBlockingPoolSize(1)
      ;

    logger.info("Vertx options: eventLoops={}, workers={}, internalBlocking={}",
        vertxOptions.getEventLoopPoolSize(),
        vertxOptions.getWorkerPoolSize(),
        vertxOptions.getInternalBlockingPoolSize());

    var vertx = Vertx.vertx(vertxOptions);

    // Create dedicated worker pool for fping batch processing
    // Pool size: 6 threads (optimal for ~11 intervals with staggered execution)
    // Max execute time: 10 seconds (fping timeout is 6s + buffer)
    fpingWorkerPool = vertx.createSharedWorkerExecutor(FPING_WORKER);
    logger.info("Created fping worker pool: {} threads", 6);

    var verticalList = new ArrayList<Future<String>>();
    var fs = vertx.fileSystem();

    verticalList.add(vertx.deployVerticle(new Distributor()));
    verticalList.add(vertx.deployVerticle(new FileWriter()));

    Future.all(verticalList)
          .onFailure(throwable -> {
            logger.error("Failed to deploy verticles", throwable);
            System.exit(1);
          })
          .onSuccess(result -> {
            logger.info("All verticles deployed successfully");

            fs.readFile(PATH)
              .onComplete(buffer -> {
                if (buffer.succeeded()) {
                  var json = new JsonObject().put(DATA, buffer.result().toString());
                  logger.info("Published CONFIG_LOADED event, size={}bytes", buffer.result().length());
                  vertx.eventBus().publish(CONFIG_LOADED, json);

                  // Load whitelist after config is loaded
                  var whitelistCsv = System.getProperty("ip.whitelist", "");
                  var whitelistFile = System.getProperty("ip.whitelist.file", "");

                  if (!whitelistFile.isEmpty()) {
                      LogConfig.loadWhitelistFromFile(whitelistFile);
                  } else {
                      LogConfig.loadWhitelist(whitelistCsv);
                  }
                } else {
                  logger.error("Failed to read config file: {}", PATH, buffer.cause());
                  System.exit(1);
                }
              })
              .onFailure(throwable -> {
                logger.error("Failed to read config file: {}", PATH, throwable);
                System.exit(1);
              })
            ;
          })
    ;

    // Cleanup on shutdown
    Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> {
              logger.info("Shutdown initiated, closing resources...");
              if (fpingWorkerPool != null)
              {
                fpingWorkerPool.close();
                logger.info("Fping worker pool closed");
              }
              vertx.close();
              logger.info("=== URL Poller Stopped ===");
            }));
  }
}
