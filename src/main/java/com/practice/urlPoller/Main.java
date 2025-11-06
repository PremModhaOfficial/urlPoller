package com.practice.urlPoller;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Main entry point for URL Poller (Database-First Mode)
 * <p>
 * Changes from file-based mode:
 * - Removed CONFIG_LOADED event and file loading
 * - IPs are now managed via REST API only
 * - Distributor queries PostgreSQL directly
 */
public class Main
{
    public static final String FPING_WORKER = "fping-worker";
    public static final String IP_WHITELIST = "ip.whitelist";
    public static final String IP_WHITELIST_FILE = "ip.whitelist.file";
    private static final int PORT = 8080;
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
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
        logger.info("URL Poller Starting (Database-First Mode)");

        // Configure Vert.x with optimized thread pools
        // Event loops: 2 (sufficient for 2 verticles + event-driven processing)
        // Default worker pool: 1 (minimum required by Vert.x, not actively used)
        // Internal blocking pool: 1 (minimal file I/O: CSV writes only)
        var vertxOptions = new VertxOptions().setEventLoopPoolSize(2)
            .setWorkerPoolSize(1)
            .setInternalBlockingPoolSize(1);

        logger.info("Vertx options: eventLoops={}, workers={}, internalBlocking={}",
                    vertxOptions.getEventLoopPoolSize(),
                    vertxOptions.getWorkerPoolSize(),
                    vertxOptions.getInternalBlockingPoolSize()
        );

        var vertx = Vertx.vertx(vertxOptions);

        // Start HTTP server for REST API
        new Server(vertx, PORT).startServer();
        logger.info("REST API server starting on port {}", PORT);

        // Create dedicated worker pool for fping batch processing
        // Pool size: 3 threads (optimal for database-driven batch processing)
        // Max execute time: 10 seconds (fping timeout is 6s + buffer)
        fpingWorkerPool = vertx.createSharedWorkerExecutor(
            FPING_WORKER, 3, 10_000_000_000L // 10 seconds max execution time
        );
        logger.info("Created fping worker pool: {} threads", 3);

        // Load IP whitelist for logging (optional)
        var whitelistCsv = System.getProperty(IP_WHITELIST, "");
        var whitelistFile = System.getProperty(IP_WHITELIST_FILE, "");

        if (!whitelistFile.isEmpty())
        {
            LogConfig.loadWhitelistFromFile(whitelistFile);
        } else if (!whitelistCsv.isEmpty())
        {
            LogConfig.loadWhitelist(whitelistCsv);
        }

        // Deploy verticles
        var verticalList = new ArrayList<Future<String>>();
        verticalList.add(vertx.deployVerticle(new Distributor()));
        verticalList.add(vertx.deployVerticle(new FileWriter()));

        Future.all(verticalList)
            .onFailure(throwable -> {
                logger.error("Failed to deploy verticles", throwable);
                System.exit(1);
            })
            .onSuccess(result -> {
                logger.info("All verticles deployed successfully");
                logger.info("System ready - Distributor polling database every 5 seconds");
                logger.info("REST API available at http://localhost:{}", PORT);
                logger.info("   POST   /ip       - Add new IP");
                logger.info("   GET    /ip       - List all IPs");
                logger.info("   GET    /ip/:id   - Get IP by ID");
                logger.info("   PUT    /ip/:id   - Update IP");
                logger.info("   DELETE /ip/:id   - Delete IP");
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
                logger.info("URL Poller Stopped");
            }));
    }
}
