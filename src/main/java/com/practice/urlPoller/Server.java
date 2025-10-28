package com.practice.urlPoller;

import com.practice.urlPoller.DB.PostgresClient;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class Server
{

    private static final String MESSAGE = "message";
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private final Vertx vertx;
    private final int PORT;
    private final PostgresClient client;


    Server(Vertx vertx, int port)
    {
        this.vertx = vertx;
        this.PORT = port;
        client = new PostgresClient(vertx);
    }

    public void startServer()
    {
        var router = Router.router(vertx);


        router.route()
            .handler(BodyHandler.create());


        router.route()
            .handler(this::loggingHandler);


        router.get("/")
            .handler(ctx -> ctx.response()
                .end(JsonObject.of(MESSAGE, "ok")
                         .encode()));

        // GET /ip - List all IPs
        router.get("/ip")
            .handler(ctx -> {
                LOG.info("GET /ip - Listing all IPs");
                client.getAllIPs()
                    .onSuccess(ips -> {
                        LOG.debug("Retrieved {} IPs", ips.size());
                        ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(200)
                            .end(new io.vertx.core.json.JsonArray(ips).encode());
                    })
                    .onFailure(err -> {
                        LOG.error("Failed to get all IPs", err);
                        ctx.fail(500, err);
                    });
            });

        router.get("/ips")
            .handler(ctx -> client.getAllIPs()
                .onSuccess(ips -> ctx.response()
                    .end(new JsonObject()
                             .put("count", ips.size())
                             .put("ips", ips)
                             .encode()))
                .onFailure(t -> {
                    LOG.error("Failed to get all IPs", t);
                    ctx.fail(500);
                }));

        router.delete("/ip/:id")
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    client.deleteIP(id)
                        .onSuccess(v -> ctx.response()
                            .end(JsonObject.of(MESSAGE, "IP deleted successfully")
                                     .encode()))
                        .onFailure(t -> {
                            LOG.error("Failed to delete IP: id={}", id, t);
                            ctx.fail(500);
                        });
                } catch (NumberFormatException e)
                {
                    ctx.fail(400);
                }
            });
        router.get("/ip/:id")
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    client.getIPById(id)
                        .onSuccess(ip -> {
                            if (ip == null)
                            {
                                ctx.response()
                                    .setStatusCode(404)
                                    .end(JsonObject.of(MESSAGE, "IP not found")
                                             .encode());
                            } else
                            {
                                ctx.response()
                                    .end(ip.encode());
                            }
                        })
                        .onFailure(t -> {
                            LOG.error("Failed to get IP: id={}", id, t);
                            ctx.fail(500);
                        });
                } catch (NumberFormatException e)
                {
                    ctx.fail(400);
                }
            });

        router.put("/ip/:id")
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    var body = ctx.body()
                        .asJsonObject();
                    if (body == null)
                    {
                        ctx.fail(400);
                        return;
                    }

                    var ip = body.getString("ip");
                    var pollInterval = body.getInteger("pollInterval");

                    if (Objects.isNull(ip) || Objects.isNull(pollInterval))
                    {
                        ctx.fail(400);
                        return;
                    }

                    client.updateIP(id, ip, pollInterval)
                        .onSuccess(v -> ctx.response()
                            .end(JsonObject.of(MESSAGE, "IP updated successfully")
                                     .encode()))
                        .onFailure(t -> {
                            LOG.error("Failed to update IP: id={}", id, t);
                            ctx.fail(500);
                        });
                } catch (NumberFormatException e)
                {
                    ctx.fail(400);
                }
            });

        router.errorHandler(403, ctx -> ctx.response()
            .setStatusCode(403)
            .end(JsonObject.of(MESSAGE, "NOT ALLOWED")
                     .encode())
        );

        router.get("/ip")
            .handler(ctx -> {
                client.getAllIPs()
                    .onSuccess(ips -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonArray(ips).encode()))
                    .onFailure(t -> {
                        LOG.error("Failed to get all IPs", t);
                        ctx.fail(500);
                    });
            });

        router.post("/ip")
            .handler(ctx -> {
                var body = ctx.body()
                    .asJsonObject();
                if (body == null)
                {
                    ctx.fail(400);
                    return;
                }
                var ip = body.getString("ip");
                var pollInterval = body.getInteger("pollInterval");

                if (Objects.isNull(pollInterval) || Objects.isNull(ip))
                {
                    ctx.fail(400);
                    return;
                }

                client.addIP(ip, pollInterval)
                    .onSuccess(v -> ctx.response()
                        .setStatusCode(201)
                        .end(JsonObject.of(MESSAGE, "IP added")
                                 .encode()))
                    .onFailure(t -> {
                        // Check if it's a duplicate key violation (PostgreSQL error 23505)
                        var errorMsg = t.getMessage();
                        if (errorMsg != null && errorMsg.contains("23505"))
                        {
                            LOG.warn("Duplicate IP attempted: {}", ip);
                            ctx.response()
                                .setStatusCode(409)
                                .end(JsonObject.of(MESSAGE, "IP already exists")
                                         .encode());
                            return;
                        }
                        LOG.error("Failed to add IP", t);
                        ctx.fail(500);
                    });

            });


        vertx.createHttpServer()
            .requestHandler(router)
            .listen(PORT)
            .onSuccess(server -> LOG.info("server started on {}", server.actualPort()))
            .onFailure(problem -> LOG.info("server failed to start", problem.getCause()));

    }


    private void loggingHandler(RoutingContext ctx)
    {
        var body = "";
        if (ctx.body() != null && ctx.body()
            .asJsonObject() != null)
        {
            body = ctx.body()
                .asJsonObject()
                .encodePrettily();
        }

        LOG.info("{} {} {}", ctx.request()
            .method()
            .toString(), ctx.request()
                     .path(), body
        );


        ctx.next();
    }
}

