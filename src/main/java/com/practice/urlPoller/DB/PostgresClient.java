package com.practice.urlPoller.DB;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.impl.PgPoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresClient
{

    public static final String DB_NAME = "MAIN";
    public static final String IP_TABLE = "ip_table";
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
                              .setDatabase(IP_TABLE)
                              .setUser(POSTGRES)
                              .setPassword(POSTGRES_PASS))
            .with(new PgPoolOptions().setName(DB_NAME)
                      .setMaxSize(2))
            .using(vertx)
            .build();


    }

    public Future<RowSet<Row>> addIP(String ip, String pollInterval)
    {
        // TODO log
        var qry = client.preparedQuery("""
                                           INSERT INTO ips (ip, pollInterval) VALUES(?,?);
                                           """);

        return qry.execute(Tuple.of(ip, pollInterval))
            .onComplete(result -> {
                if (result.failed())
                {
                    LOG.error("problem", result.cause());

                    throw new RuntimeException(result.cause());
                }
            });
    }
}
