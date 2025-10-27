package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static com.practice.urlPoller.Constants.Event.CONFIG_LOADED;
import static com.practice.urlPoller.Constants.JsonFields.DATA;

public class Main
{
  public static final String FPING_WORKER = "fping-worker";
  public static final String IP_WHITELIST = "ip.whitelist";
  public static final String IP_WHITELIST_FILE = "ip.whitelist.file";
  public static final int PORT = 8080;
  private static final Logger logger = LoggerFactory.getLogger(Main.class);
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

    // Configure Vert.x with optimized thread pools
    // Event loops: 2 (sufficient for 2 verticles + event-driven processing)
    // Default worker pool: 1 (minimum required by Vert.x, not actively used)
    // Internal blocking pool: 1 (minimal file I/O: config load + CSV writes)
    var vertxOptions = new VertxOptions().setEventLoopPoolSize(2)
      .setWorkerPoolSize(1)
      .setInternalBlockingPoolSize(1);

    logger.info("Vertx options: eventLoops={}, workers={}, internalBlocking={}", vertxOptions.getEventLoopPoolSize(), vertxOptions.getWorkerPoolSize(), vertxOptions.getInternalBlockingPoolSize());

    var vertx = Vertx.vertx(vertxOptions);
    new Server(vertx, PORT).startServer();


    // Create dedicated worker pool for fping batch processing
    // Pool size: 3 threads (optimal for GCD-based timer + batch processing)
    // Reasoning: GCD timer fires once per interval, single batch per cycle
    //            3 workers handle edge cases where timer cycles overlap
    // Max execute time: 10 seconds (fping timeout is 6s + buffer)
    fpingWorkerPool = vertx.createSharedWorkerExecutor(
      FPING_WORKER, 1, 10_000_000_000L // 10 seconds max execution time
    );
    logger.info("Created fping worker pool: {} threads (reduced from 20 default)", 3);

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
            if (buffer.succeeded())
            {
              var json = new JsonObject().put(DATA, buffer.result()
                .toString()
              );
              logger.info("Published CONFIG_LOADED event, size={}bytes", buffer.result()
                .length()
              );
              vertx.eventBus()
                .publish(CONFIG_LOADED, json);

              // Load whitelist after config is loaded
              var whitelistCsv = System.getProperty(IP_WHITELIST, "");
              var whitelistFile = System.getProperty(IP_WHITELIST_FILE, "");

              if (!whitelistFile.isEmpty())
              {
                LogConfig.loadWhitelistFromFile(whitelistFile);
              } else
              {
                LogConfig.loadWhitelist(whitelistCsv);
              }
            } else
            {
              logger.error("Failed to read config file: {}", PATH, buffer.cause());
              System.exit(1);
            }
          })
          .onFailure(throwable -> {
            logger.error("Failed to read config file: {}", PATH, throwable);
            System.exit(1);
          });
      });

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
