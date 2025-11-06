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

import static com.practice.urlPoller.Constants.JsonFields.IP;
import static com.practice.urlPoller.Constants.JsonFields.POLL_INTERVAL;

public class Server
{

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
                .end(ResponseBuilder.success("API is running", 200)
                         .encode()));

        // GET /ip - List all IPs
        // GET /ip - List all IPs (moved to avoid duplicate)
        router.get("/ip")
            .handler(ctx -> {
                LOG.info("GET /ip - Listing all IPs with status");
                client.getAllIPsWithStatus()
                    .onSuccess(ips -> {
                        LOG.debug("Retrieved {} IPs with status", ips.size());
                        ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(200)
                            .end(ResponseBuilder.success("IPs retrieved successfully", new JsonArray(ips), 200)
                                     .encode());
                    })
                    .onFailure(err -> {
                        LOG.error("Failed to get all IPs with status", err);
                        ctx.response()
                            .setStatusCode(500)
                            .end(ResponseBuilder.error("Failed to retrieve IPs", 500)
                                     .encode());
                    });
            });

        router.get("/ips")
            .handler(ctx -> client.getAllIPsWithStatus()
                .onSuccess(ips -> ctx.response()
                    .end(ResponseBuilder.success("IPs retrieved successfully", new JsonObject()
                            .put("count", ips.size())
                            .put("ips", new JsonArray(ips)), 200
                        )
                             .encode()))
                .onFailure(t -> {
                    LOG.error("Failed to get all IPs with status", t);
                    ctx.response()
                        .setStatusCode(500)
                        .end(ResponseBuilder.error("Failed to retrieve IPs", 500)
                                 .encode());
                }));

        router.delete("/ip/:id")
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    client.deleteIP(id)
                        .onSuccess(data -> ctx.response()
                            .setStatusCode(200)
                            .end(ResponseBuilder.success("IP deleted successfully", data, 200)
                                     .encode()))
                        .onFailure(t -> {
                            LOG.error("Failed to delete IP: id={}", id, t);
                            ctx.response()
                                .setStatusCode(500)
                             .end(ResponseBuilder.error("Failed to delete IP", 500)
                                          .encode());
                        });
                } catch (NumberFormatException e)
                {
                    ctx.response()
                        .setStatusCode(400)
                        .end(ResponseBuilder.error("Invalid ID format", 400)
                                 .encode());
                }
            });
        router.get("/ip/:id")
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    client.getIPByIdWithStatus(id)
                        .onSuccess(ip -> {
                            if (ip == null)
                            {
                                ctx.response()
                                    .setStatusCode(404)
                                    .end(ResponseBuilder.error("IP not found", 404)
                                             .encode());
                            } else
                            {
                                ctx.response()
                                    .end(ResponseBuilder.success("IP retrieved successfully", ip, 200)
                                             .encode());
                            }
                        })
                        .onFailure(t -> {
                            LOG.error("Failed to get IP with status: id={}", id, t);
                            ctx.response()
                                .setStatusCode(500)
                                .end(ResponseBuilder.error("Failed to retrieve IP", 500)
                                         .encode());
                        });
                } catch (NumberFormatException e)
                {
                    ctx.response()
                        .setStatusCode(400)
                        .end(ResponseBuilder.error("Invalid ID format", 400)
                                 .encode());
                }
            });

        router.put("/ip/:id")
            .handler(this::validateIPRequestHandler)
            .handler(ctx -> {
                try
                {
                    var id = Integer.parseInt(ctx.pathParam("id"));
                    var body = ctx.body()
                        .asJsonObject();
                    var ip = body.getString(IP);
                    var pollInterval = body.getInteger(POLL_INTERVAL);

                    client.updateIP(id, ip, pollInterval)
                        .onSuccess(data -> ctx.response()
                            .setStatusCode(200)
                            .end(ResponseBuilder.success("IP updated successfully", data, 200)
                                     .encode()))
                        .onFailure(t -> {
                            LOG.error("Failed to update IP: id={}", id, t);
                            ctx.response()
                                .setStatusCode(500)
                                .end(ResponseBuilder.error("Failed to update IP", 500)
                                         .encode());
                        });
                } catch (NumberFormatException e)
                {
                    ctx.response()
                        .setStatusCode(400)
                        .end(ResponseBuilder.error("Invalid ID format", 400)
                                 .encode());
                }
            });

        router.errorHandler(403, ctx -> ctx.response()
            .setStatusCode(403)
            .end(ResponseBuilder.error("NOT ALLOWED", 403)
                     .encode())
        );


        router.post("/ip")
            .handler(this::validateIPRequestHandler)
            .handler(ctx -> {
                var body = ctx.body()
                    .asJsonObject();
                var ip = body.getString("ip");
                var pollInterval = body.getInteger(POLL_INTERVAL);

                client.addIP(ip, pollInterval)
                    .onSuccess(data -> ctx.response()
                        .setStatusCode(201)
                        .end(ResponseBuilder.success("IP added successfully", data, 201)
                                 .encode()))
                    .onFailure(t -> {
                        // Check if it's a duplicate key violation (PostgreSQL error 23505)
                        var errorMsg = t.getMessage();
                        if (errorMsg != null && errorMsg.contains("23505"))
                        {
                            LOG.warn("Duplicate IP attempted: {}", ip);
                            ctx.response()
                                .setStatusCode(409)
                                .end(ResponseBuilder.error("IP already exists", 409)
                                         .encode());
                            return;
                        }
                        LOG.error("Failed to add IP", t);
                        ctx.response()
                            .setStatusCode(500)
                            .end(ResponseBuilder.error("Failed to add IP", 500)
                                     .encode());
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
                     .toString(),
                 ctx.request()
                     .path(),
                 body
        );

        ctx.next();
    }

    private void validateIPRequestHandler(RoutingContext ctx)
    {
        var body = ctx.body()
            .asJsonObject();
        if (body == null)
        {
            ctx.response()
                .setStatusCode(400)
                .end(ResponseBuilder.error("Request body is required", 400)
                         .encode());
            return;
        }
        var ip = body.getString("ip");
        var pollInterval = body.getInteger(POLL_INTERVAL);
        if (Objects.isNull(ip) || Objects.isNull(pollInterval))
        {
            ctx.response()
                .setStatusCode(400)
                .end(ResponseBuilder.error("IP and pollInterval are required", 400)
                         .encode());
            return;
        }
        ctx.next();
    }
}
