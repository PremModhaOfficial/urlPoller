package com.practice.urlPoller;


import com.practice.urlPoller.DB.PostgresClient;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.practice.urlPoller.Constants.Event.*;

/**
 * Database-First Distributor
 * <p>
 * Replaces HashMap countdown with PostgreSQL timestamp-based polling.
 * Queries database every 5 seconds for IPs where next_poll_time <= NOW().
 * <p>
 * Architecture:
 * - No in-memory state (all data in PostgreSQL)
 * - Clock-based scheduling (no drift)
 * - Crash-safe (state persists in DB)
 * - Immediate API changes (< 5s delay)
 */
public class Distributor extends VerticleBase
{
    private static final Logger logger = LoggerFactory.getLogger(Distributor.class);
    // Polling configuration
    private static final int POLLING_CHECK_INTERVAL_SEC = 5;
    private static final long POLLING_CHECK_INTERVAL_MS = POLLING_CHECK_INTERVAL_SEC * 1000L;
    // Database client
    private PostgresClient dbClient;

    @Override
    public Future<?> start()
    {
        logger.info("Starting Distributor (database-first mode)...");

        // Initialize PostgreSQL client
        dbClient = new PostgresClient(vertx);

        // Setup event listeners for API operations (logging only)
        setupEventListeners();

        // Start main polling timer (queries DB every 5 seconds)
        vertx.setPeriodic(POLLING_CHECK_INTERVAL_MS, id -> pollDueIPs());

        logger.info("Distributor started successfully");
        logger.info("   - Polling interval: {}s", POLLING_CHECK_INTERVAL_SEC);
        logger.info("   - Query: SELECT * FROM ips WHERE next_poll_time <= NOW()");

        return Future.succeededFuture();
    }

    /**
     * Setup event bus listeners for CRUD operations (informational only)
     * Actual polling is driven by database timestamps, not events
     */
    private void setupEventListeners()
    {
        vertx.eventBus()
            .<JsonObject>localConsumer(IP_ADDED, msg -> {
                                           var body = msg.body();
                                           logger.info("New IP added: id={}, ip={}, pollInterval={}s",
                                                       body.getInteger("id"),
                                                       body.getString("ip"),
                                                       body.getInteger("pollInterval")
                                           );
                                       }
            );

        vertx.eventBus()
            .<JsonObject>localConsumer(IP_UPDATED, msg -> {
                                           var body = msg.body();
                                           logger.info("IP updated: id={}, ip={}, pollInterval={}s",
                                                       body.getInteger("id"),
                                                       body.getString("ip"),
                                                       body.getInteger("pollInterval")
                                           );
                                       }
            );

        vertx.eventBus()
            .<JsonObject>localConsumer(IP_DELETED, msg -> {
                                           var body = msg.body();
                                           logger.info("IP deleted: id={}, ip={}",
                                                       body.getInteger("id"),
                                                       body.getString("ip")
                                           );
                                       }
            );
    }

    /**
     * Core polling method - queries database for IPs due for polling
     * <p>
     * Flow:
     * 1. Query DB: SELECT * FROM ips WHERE next_poll_time <= NOW()
     * 2. Group IPs by poll interval (for batch efficiency)
     * 3. Execute fping for each interval group
     * 4. Update next_poll_time after successful ping
     */
    private void pollDueIPs()
    {
        dbClient.getIPsDueForPoll()
            .onSuccess(ips -> {
                if (ips.isEmpty())
                {
                    logger.trace("No IPs due for polling this cycle");
                    return;
                }

                logger.info("Found {} IPs due for polling", ips.size());

                // Group IPs by poll interval for efficient batch processing
                Map<Integer, Set<String>> ipsByInterval = new HashMap<>();
                Map<String, JsonObject> ipMetadata = new HashMap<>();

                ips.forEach(json -> {
                    int interval = json.getInteger("pollInterval");
                    var ip = json.getString("ip");

                    ipsByInterval.computeIfAbsent(interval, k -> new HashSet<>())
                        .add(ip);
                    ipMetadata.put(ip, json);
                });

                logger.debug("Grouped into {} interval buckets", ipsByInterval.size());

                // Execute batch ping for each interval group
                ipsByInterval.forEach((interval, ipSet) -> {
                    logger.debug("Batch polling {} IPs with {}s interval", ipSet.size(), interval);

                    FpingWorker.work(vertx, ipSet, interval)
                        .onSuccess(results -> {
                            logger.debug("Batch ping succeeded for {} IPs", ipSet.size());

                            // Collect IPs to update (all successfully pinged IPs)
                            var toUpdate = ipSet.stream()
                                .map(ipMetadata::get)
                                .toList();

                            // Batch update next_poll_time in database
                            dbClient.batchUpdateNextPollTimes(toUpdate)
                                .onSuccess(v -> logger.debug("Updated next_poll_time for {} IPs", toUpdate.size()))
                                .onFailure(err -> logger.error("Failed to update next_poll_time for {} IPs", toUpdate.size(), err));
                        })
                        .onFailure(err -> {
                            logger.error("FpingWorker failed for {} IPs with {}s interval", ipSet.size(), interval, err);
                            // Note: We do NOT update next_poll_time on failure
                            // This allows retry on next polling cycle
                        });
                });
            })
            .onFailure(err -> logger.error("Failed to query IPs due for polling", err));
    }

}

