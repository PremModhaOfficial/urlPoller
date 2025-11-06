package com.practice.urlPoller;

import io.vertx.core.json.JsonObject;

/**
 * Utility class for creating and formatting ping result JSON objects.
 * Follows Single Responsibility Principle - handles only ping result data operations.
 *
 * <p>This class provides factory methods for creating ping result JSON objects
 * and formatting methods for CSV output. All methods are static as this is a
 * utility class with no state.
 *
 * <p>Thread-safe: All methods are stateless and work with immutable JsonObject instances.
 */
public final class PingResultUtil
{
    // JSON Keys - Constants for type-safe JSON object access
    public static final String IP = "ip";
    public static final String SUCCESS = "isSuccess";
    public static final String MIN_RTT = "minRtt";
    public static final String AVG_RTT = "avgRtt";
    public static final String MAX_RTT = "maxRtt";
    public static final String PACKET_LOSS = "packetLoss";
    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";
    // CSV Format Constants
    private static final String CSV_FORMAT_UP = "%s,%d%%,%.2f,%.2f,%.2f";
    private static final String CSV_FORMAT_DOWN = "%s,%d%%,-,-,-";

    /**
     * Private constructor to prevent instantiation of utility class.
     * Follows utility class pattern - all methods are static.
     */
    private PingResultUtil()
    {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Factory method to create a successful ping result JSON object.
     * Follows Single Responsibility Principle - only creates success results.
     *
     * @param ip         The IP address that was pinged (must not be null or blank)
     * @param minRtt     Minimum round-trip time in milliseconds (must be >= 0)
     * @param avgRtt     Average round-trip time in milliseconds (must be >= 0)
     * @param maxRtt     Maximum round-trip time in milliseconds (must be >= 0)
     * @param packetLoss Packet loss percentage (0-100)
     * @return Immutable JsonObject containing the ping result data
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public static JsonObject createSuccessResult(
        String ip,
        double minRtt,
        double avgRtt,
        double maxRtt,
        int packetLoss)
    {
        validateIp(ip);
        validateRtt(minRtt, "minRtt");
        validateRtt(avgRtt, "avgRtt");
        validateRtt(maxRtt, "maxRtt");
        validatePacketLoss(packetLoss);

        return new JsonObject()
            .put(IP, ip)
            .put(SUCCESS, true)
            .put(MIN_RTT, minRtt)
            .put(AVG_RTT, avgRtt)
            .put(MAX_RTT, maxRtt)
            .put(PACKET_LOSS, packetLoss);
    }

    /**
     * Factory method to create an unreachable/failed ping result JSON object.
     * Follows Single Responsibility Principle - only creates failure results.
     *
     * @param ip The IP address that was unreachable (must not be null or blank)
     * @return Immutable JsonObject representing an unreachable host (100% packet loss)
     * @throws IllegalArgumentException if ip is null or blank
     */
    public static JsonObject createUnreachableResult(String ip)
    {
        validateIp(ip);

        return new JsonObject()
            .put(IP, ip)
            .put(SUCCESS, false)
            .put(MIN_RTT, -1.0)
            .put(AVG_RTT, -1.0)
            .put(MAX_RTT, -1.0)
            .put(PACKET_LOSS, 100);
    }

    /**
     * Format a ping result JSON object as a CSV row.
     * Follows Single Responsibility Principle - only handles formatting.
     *
     * <p>Output format:
     * <ul>
     *   <li>Success: "UP,LOSS%,MIN_RTT,AVG_RTT,MAX_RTT"</li>
     *   <li>Failure: "DOWN,100%,-,-,-"</li>
     * </ul>
     *
     * @param result JsonObject containing ping result data (must not be null)
     * @return Formatted CSV row string without trailing newline
     * @throws IllegalArgumentException if result is null or missing required fields
     */
    public static String toCsvRow(JsonObject result)
    {
        if (result == null)
        {
            throw new IllegalArgumentException("Ping result JSON cannot be null");
        }

        var isSuccess = result.getBoolean(SUCCESS, false);
        var status = isSuccess ? STATUS_UP : STATUS_DOWN;
        var packetLoss = result.getInteger(PACKET_LOSS);

        if (isSuccess)
        {
            var minRtt = result.getDouble(MIN_RTT);
            var avgRtt = result.getDouble(AVG_RTT);
            var maxRtt = result.getDouble(MAX_RTT);
            return String.format(CSV_FORMAT_UP, status, packetLoss, minRtt, avgRtt, maxRtt);
        } else
        {
            return String.format(CSV_FORMAT_DOWN, status, packetLoss);
        }
    }

    // ==================== Validation Methods ====================
    // Follow Single Responsibility - each validates one concern

    /**
     * Validate IP address is not null or blank.
     */
    private static void validateIp(String ip)
    {
        if (ip == null || ip.isBlank())
        {
            throw new IllegalArgumentException("IP address cannot be null or blank");
        }
    }

    /**
     * Validate RTT value is non-negative.
     */
    private static void validateRtt(double rtt, String fieldName)
    {
        if (rtt < 0)
        {
            throw new IllegalArgumentException(fieldName + " cannot be negative: " + rtt);
        }
    }

    /**
     * Validate packet loss is within valid range (0-100).
     */
    private static void validatePacketLoss(int packetLoss)
    {
        if (packetLoss < 0 || packetLoss > 100)
        {
            throw new IllegalArgumentException("Packet loss must be 0-100%: " + packetLoss);
        }
    }
}
