package com.practice.urlPoller;

import com.practice.urlPoller.DB.PostgresClient;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            .handler(this::loggingHandler);


        router.get("/")
            .handler(ctx -> ctx.response()
                .end(JsonObject.of(MESSAGE, "ok")
                         .encode()));

        router.route()
            .handler(BodyHandler.create());

        router.delete("/ip/:id")
            .handler(ctx -> {
                ctx.response()
                    .end(JsonObject.of(MESSAGE, ctx.pathParam("id") + " delete requested")
                             .encode());
            });
        router.get("/ip/:id")
            .handler(ctx -> {
                ctx.response()
                    .end(JsonObject.of(MESSAGE, ctx.pathParam("id") + " details requested")
                             .encode());
            });

        router.put("/ip/:id")
            .handler(ctx -> {
                ctx.response()
                    .end(JsonObject.of(MESSAGE, ctx.pathParam("id") + " update requested")
                             .encode());
            });

        router.post("/ip")
            .handler(ctx -> {
                var ip = ctx.pathParam("ip");
                var pollInterval = ctx.pathParam("pollInterval");

                var future = client.addIP(ip, pollInterval);
                future.onFailure(ignore -> ctx.fail(500));

            });


        vertx.createHttpServer()
            .requestHandler(router)
            .listen(PORT)
            .onSuccess(server -> LOG.info("server started on {}", server.actualPort()))
            .onFailure(problem -> LOG.info("server failed to start", problem.getCause()));

    }


    private void loggingHandler(RoutingContext ctx)
    {
        LOG.info("{} {}", ctx.request()
            .method()
            .toString(), ctx.request()
                     .path()
        );


        ctx.next();
    }
}

