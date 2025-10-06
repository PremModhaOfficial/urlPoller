package com.practice.urlPoller;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for fping output format using concurrent parsing for large result sets.
 * Thread-safe implementation using ConcurrentHashMap.
 * Expected fping output format (-c 1 -q):
 * 8.8.8.8     : xmt/rcv/%loss = 1/1/0%, min/avg/max = 15.2/15.2/15.2
 * 192.168.1.1 : xmt/rcv/%loss = 1/0/100%
 */
public class FpingParser
{
  private static final Logger logger = LoggerFactory.getLogger(FpingParser.class);

  // Pattern to match fping output line
  // Group 1: IP address
  // Group 2: packets sent (xmt)
  // Group 3: packets received (rcv)
  // Group 4: packet loss percentage
  // Group 5-7: min/avg/max RTT (optional - only present if host responded)
  private static final Pattern FPING_PATTERN = Pattern.compile(
    "^\\s*(\\S+)\\s+:\\s+xmt/rcv/%loss\\s+=\\s+(\\d+)/(\\d+)/(\\d+)%(?:,\\s+min/avg/max\\s+=\\s+([\\d.]+)/([\\d.]+)/([\\d.]+))?"
  );

  /**
   * Parse fping output into a map of IP -> PingResult using parallel streams for performance.
   * Thread-safe using ConcurrentHashMap.
   *
   * @param fpingOutput The complete stdout from fping command
   * @param batchId     Unique batch ID for correlation
   * @return ConcurrentHashMap mapping each IP to its PingResult
   */
  public static Map<String, PingResult> parse(String fpingOutput, long batchId)
  {
    if (fpingOutput == null || fpingOutput.isBlank())
    {
      logger.debug("[Batch:{}] Empty fping output", batchId);
      return new ConcurrentHashMap<>();
    }

    logger.debug("[Batch:{}] Parsing started: lines={}", batchId, fpingOutput.split("\n").length);

    // Use parallel stream for concurrent parsing of large result sets

    return fpingOutput.lines()
                      .parallel()  // Enable parallel processing for 1000+ IPs
                      .map(String::strip)
                      .filter(line -> !line.isEmpty())
                      .filter(line -> FPING_PATTERN.matcher(line)
                                                   .find())  // Only process valid fping lines
                      .map(FpingParser::parseLine)
                      .filter(Objects::nonNull)  // Skip any parsing failures
                      .collect(
                        ConcurrentHashMap::new,  // Thread-safe map
                        (map, result) -> map.put(result.getIp(), result),
                        ConcurrentHashMap::putAll  // Thread-safe merge for parallel streams
                      );
  }

  /**
   * Parse a single fping output line into a PingResult.
   * Thread-safe - no shared state.
   *
   * @param line Single line from fping output
   * @return PingResult or null if parsing fails
   */
  private static PingResult parseLine(String line)
  {
    Matcher matcher = FPING_PATTERN.matcher(line);

    if (!matcher.find())
    {
      logger.warn("Failed to parse fping line: '{}'", line);
      return null;
    }

    try
    {
      String ip = matcher.group(1);
      int packetsReceived = Integer.parseInt(matcher.group(3));
      int packetLoss = Integer.parseInt(matcher.group(4));

      boolean isAlive = packetsReceived > 0;

      // RTT values are only present if host responded
      if (isAlive && matcher.group(5) != null)
      {
        double minRtt = Double.parseDouble(matcher.group(5));
        double avgRtt = Double.parseDouble(matcher.group(6));
        double maxRtt = Double.parseDouble(matcher.group(7));

        return new PingResult(ip, true, minRtt, avgRtt, maxRtt,
          packetLoss);
      } else
      {
        // Host unreachable
        return PingResult.unreachable(ip);
      }
    } catch (NumberFormatException numberFormatException)
    {
      logger.error("Failed to parse numbers in fping line: '{}', error={}", line, numberFormatException.getMessage());
      return null;
    }
  }

}
