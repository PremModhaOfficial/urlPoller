package com.practice.urlPoller;

import com.practice.urlPoller.Constants.JsonFields;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ResponseBuilder
{

    public static JsonObject success(String message, JsonObject data, int httpStatus)
    {
        return new JsonObject()
            .put(JsonFields.STATUS, "success")
            .put(JsonFields.MESSAGE, message)
            .put(JsonFields.HTTP_STATUS, httpStatus)
            .put(JsonFields.DATA, data);
    }

    public static JsonObject success(String message, JsonArray data, int httpStatus)
    {
        return new JsonObject()
            .put(JsonFields.STATUS, "success")
            .put(JsonFields.MESSAGE, message)
            .put(JsonFields.HTTP_STATUS, httpStatus)
            .put(JsonFields.DATA, data);
    }

    public static JsonObject success(String message, int httpStatus)
    {
        return new JsonObject()
            .put(JsonFields.STATUS, "success")
            .put(JsonFields.MESSAGE, message)
            .put(JsonFields.HTTP_STATUS, httpStatus)
            .put(JsonFields.DATA, JsonObject.of());
    }

    public static JsonObject error(String message, int httpStatus)
    {
        return new JsonObject()
            .put(JsonFields.STATUS, "error")
            .put(JsonFields.MESSAGE, message)
            .put(JsonFields.HTTP_STATUS, httpStatus)
            .put(JsonFields.DATA, null);
    }

}
