package com.practice.urlPoller.DB;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.impl.PgPoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class PostgresClient
{

    public static final String DB_NAME = "MAIN";
    public static final String LOCALHOST = "localhost";
    public static final String POSTGRES = "postgres";
    public static final String POSTGRES_PASS = "postgres";
    private static final Logger LOG = LoggerFactory.getLogger(PostgresClient.class);
    private final SqlClient client;

    public PostgresClient(Vertx vertx)
    {
        this.client = PgBuilder.client()
            .connectingTo(new PgConnectOptions().setPort(5432)
                              .setHost(LOCALHOST)
                              .setDatabase(POSTGRES)
                              .setUser(POSTGRES)
                              .setPassword(POSTGRES_PASS))
            .with(new PgPoolOptions().setName(DB_NAME)
                      .setMaxSize(2))
            .using(vertx)
            .build();

        LOG.info("PostgreSQL client initialized: {}:{}/{}", LOCALHOST, 5432, POSTGRES);

        // Test connection immediately
        client.query("SELECT 1")
            .execute()
            .onSuccess(rs -> LOG.info("✅ PostgreSQL connection verified"))
            .onFailure(err -> LOG.error("❌ PostgreSQL connection failed", err));
    }

    // =====================================================
    // CREATE Operations
    // =====================================================

    /**
     * Add a new IP with timestamp-based polling
     *
     * @param ip           IP address
     * @param pollInterval Polling interval in seconds
     * @return Future with the new IP's ID
     */
    public Future<Integer> addIP(String ip, int pollInterval)
    {
        LOG.debug("Adding IP: ip={}, pollInterval={}s", ip, pollInterval);

        var sql = "INSERT INTO ips (ip, poll_interval, next_poll_time) " +
            "VALUES ($1, $2, NOW() + ($3 || ' seconds')::INTERVAL) " +
            "RETURNING id";

        return client.preparedQuery(sql)
            .execute(Tuple.of(ip, pollInterval, String.valueOf(pollInterval)))
            .map(rows -> {
                int id = rows.iterator()
                    .next()
                    .getInteger("id");
                LOG.info("✅ IP added: id={}, ip={}, pollInterval={}s", id, ip, pollInterval);
                return id;
            })
            .onFailure(err -> LOG.error("❌ Failed to add IP: {}", ip, err));
    }

    // =====================================================
    // READ Operations
    // =====================================================

    /**
     * Get all IPs from database
     *
     * @return Future with list of all IPs as JsonObjects
     */
    public Future<List<JsonObject>> getAllIPs()
    {
        var sql = "SELECT * FROM ips ORDER BY id ASC";

        return client.query(sql)
            .execute()
            .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(this::rowToJson)
                .collect(Collectors.toList()))
            .onSuccess(ips -> LOG.debug("Retrieved {} IPs", ips.size()))
            .onFailure(err -> LOG.error("❌ Failed to get all IPs", err));
    }

    /**
     * Get a single IP by ID
     *
     * @param id IP record ID
     * @return Future with JsonObject or null if not found
     */
    public Future<JsonObject> getIPById(int id)
    {
        var sql = "SELECT * FROM ips WHERE id = $1";

        return client.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rows -> {
                if (rows.size() == 0)
                {
                    LOG.debug("IP not found: id={}", id);
                    return null;
                }
                return rowToJson(rows.iterator()
                                     .next());
            })
            .onFailure(err -> LOG.error("❌ Failed to get IP by id={}", id, err));
    }

    /**
     * ⭐ CORE METHOD - Get IPs that are due for polling
     *
     * @return Future with list of IPs where next_poll_time <= NOW()
     */
    public Future<List<JsonObject>> getIPsDueForPoll()
    {
        var sql = "SELECT id, ip, poll_interval FROM ips " +
            "WHERE next_poll_time <= NOW() " +
            "ORDER BY next_poll_time ASC";

        return client.query(sql)
            .execute()
            .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new JsonObject()
                    .put("id", row.getInteger("id"))
                    .put("ip", row.getString("ip"))
                    .put("pollInterval", row.getInteger("poll_interval")))
                .collect(Collectors.toList()))
            .onSuccess(ips -> {
                if (!ips.isEmpty())
                {
                    LOG.debug("Found {} IPs due for polling", ips.size());
                }
            })
            .onFailure(err -> LOG.error("❌ Failed to get IPs due for poll", err));
    }

    /**
     * Update next_poll_time for a single IP
     *
     * @param id           IP record ID
     * @param pollInterval Polling interval in seconds
     * @return Future<Void>
     */
    public Future<Void> updateNextPollTime(int id, int pollInterval)
    {
        Promise<Void> promise = Promise.promise();
        var sql = "UPDATE ips " +
            "SET next_poll_time = NOW() + ($1 || ' seconds')::INTERVAL " +
            "WHERE id = $2";

        client.preparedQuery(sql)
            .execute(Tuple.of(String.valueOf(pollInterval), id))
            .onComplete(ar -> {
                if (ar.succeeded())
                {
                    LOG.debug("Updated next_poll_time for id={}", id);
                    promise.complete();
                } else
                {
                    LOG.error("❌ Failed to update next_poll_time for id={}", id, ar.cause());
                    promise.fail(ar.cause());
                }
            });

        return promise.future();
    }

    /**
     * Batch update next_poll_time for multiple IPs (high performance)
     *
     * @param ips List of JsonObjects with "id" and "pollInterval" fields
     * @return Future<Void>
     */
    public Future<Void> batchUpdateNextPollTimes(List<JsonObject> ips)
    {
        if (ips.isEmpty())
        {
            return Future.succeededFuture();
        }

        Promise<Void> promise = Promise.promise();
        var sql = "UPDATE ips " +
            "SET next_poll_time = NOW() + ($1 || ' seconds')::INTERVAL " +
            "WHERE id = $2";

        var batch = ips.stream()
            .map(ip -> Tuple.of(String.valueOf(ip.getInteger("pollInterval")), ip.getInteger("id")))
            .collect(Collectors.toList());

        client.preparedQuery(sql)
            .executeBatch(batch)
            .onComplete(ar -> {
                if (ar.succeeded())
                {
                    LOG.debug("Batch updated {} IPs", ips.size());
                    promise.complete();
                } else
                {
                    LOG.error("❌ Failed to batch update {} IPs", ips.size(), ar.cause());
                    promise.fail(ar.cause());
                }
            });

        return promise.future();
    }

    /**
     * Update an IP's details (IP address and/or poll interval)
     *
     * @param id           IP record ID
     * @param ip           New IP address
     * @param pollInterval New polling interval in seconds
     * @return Future<Void>
     */
    public Future<Void> updateIP(int id, String ip, int pollInterval)
    {
        LOG.debug("Updating IP: id={}, ip={}, pollInterval={}s", id, ip, pollInterval);

        Promise<Void> promise = Promise.promise();
        var sql = "UPDATE ips " +
            "SET ip = $1, poll_interval = $2, " +
            "next_poll_time = NOW() + ($3 || ' seconds')::INTERVAL " +
            "WHERE id = $4";

        client.preparedQuery(sql)
            .execute(Tuple.of(ip, pollInterval, String.valueOf(pollInterval), id))
            .onComplete(ar -> {
                if (ar.succeeded())
                {
                    LOG.info("✅ IP updated: id={}, ip={}, pollInterval={}s", id, ip, pollInterval);
                    promise.complete();
                } else
                {
                    LOG.error("❌ Failed to update IP: id={}", id, ar.cause());
                    promise.fail(ar.cause());
                }
            });

        return promise.future();
    }

    /**
     * Delete an IP by ID
     *
     * @param id IP record ID
     * @return Future<Void>
     */
    public Future<Void> deleteIP(int id)
    {
        LOG.debug("Deleting IP: id={}", id);

        Promise<Void> promise = Promise.promise();
        var sql = "DELETE FROM ips WHERE id = $1";

        client.preparedQuery(sql)
            .execute(Tuple.of(id))
            .onComplete(ar -> {
                if (ar.succeeded())
                {
                    LOG.info("✅ IP deleted: id={}", id);
                    promise.complete();
                } else
                {
                    LOG.error("❌ Failed to delete IP: id={}", id, ar.cause());
                    promise.fail(ar.cause());
                }
            });

        return promise.future();
    }

    // =====================================================
    // Helper Methods
    // =====================================================

    /**
     * Convert database Row to JsonObject
     *
     * @param row SQL result row
     * @return JsonObject with all IP fields
     */
    private JsonObject rowToJson(Row row)
    {
        return new JsonObject()
            .put("id", row.getInteger("id"))
            .put("ip", row.getString("ip"))
            .put("pollInterval", row.getInteger("poll_interval"))
            .put("nextPollTime", row.getLocalDateTime("next_poll_time")
                .toString()
            )
            .put("createdAt", row.getLocalDateTime("created_at")
                .toString()
            )
            .put("updatedAt", row.getLocalDateTime("updated_at")
                .toString()
            );
    }

}
