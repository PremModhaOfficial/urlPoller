package com.practice.urlPoller;

/**
 * Data class representing the result of a ping operation for a single IP address.
 * Used to encapsulate parsed results from fping output.
 */
public class PingResult
{
  public static final String CSV_FORMAT_UP = "%s,%s,%d%%,%.2f,%.2f,%.2f";
  public static final String CSV_FORMAT_DOWN = "%s,%s,%d%%,-,-,-";
  public static final String UP = "UP";
  public static final String DOWN = "DOWN";
  private final String ip;
  private final boolean isAlive;
  private final double minRtt;      // milliseconds
  private final double avgRtt;      // milliseconds
  private final double maxRtt;      // milliseconds
  private final int packetLoss;     // percentage (0-100)

  public PingResult(String ip, boolean isAlive, double minRtt, double avgRtt, double maxRtt, int packetLoss)
  {
    this.ip = ip;
    this.isAlive = isAlive;
    this.minRtt = minRtt;
    this.avgRtt = avgRtt;
    this.maxRtt = maxRtt;
    this.packetLoss = packetLoss;
    // original fping line for debugging
  }

  // Factory method for failed/unreachable hosts
  public static PingResult unreachable(String ip)
  {
    return new PingResult(ip, false, -1, -1, -1, 100);
  }

  // Getters
  public String getIp()
  {
    return ip;
  }

  public boolean isAlive()
  {
    return isAlive;
  }

  /**
   * Format result as CSV row: IP,Status,PacketLoss%,MinRTT,AvgRTT,MaxRTT
   */
  public String toCsvRow()
  {
    String status = isAlive ? UP : DOWN;
    if (isAlive)
    {
      return String.format(CSV_FORMAT_UP, ip, status, packetLoss, minRtt, avgRtt, maxRtt);
    } else
    {
      return String.format(CSV_FORMAT_DOWN, ip, status, packetLoss);
    }
  }

  @Override
  public String toString()
  {
    return "PingResult{" + "ip='" + ip + '\'' + ", isAlive=" + isAlive + ", avgRtt=" + avgRtt + ", packetLoss=" + packetLoss + '}';
  }
}
