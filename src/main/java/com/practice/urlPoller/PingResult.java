package com.practice.urlPoller;

/**
 * Data class representing the result of a ping operation for a single IP address.
 * Used to encapsulate parsed results from fping output.
 */
public class PingResult
{
  private final String ip;
  private final boolean isAlive;
  private final double minRtt;      // milliseconds
  private final double avgRtt;      // milliseconds
  private final double maxRtt;      // milliseconds
  private final int packetsSent;
  private final int packetsReceived;
  private final int packetLoss;     // percentage (0-100)
  private final String rawOutput;   // original fping line for debugging

  public PingResult(String ip, boolean isAlive, double minRtt, double avgRtt, double maxRtt,
                    int packetsSent, int packetsReceived, int packetLoss, String rawOutput)
  {
    this.ip = ip;
    this.isAlive = isAlive;
    this.minRtt = minRtt;
    this.avgRtt = avgRtt;
    this.maxRtt = maxRtt;
    this.packetsSent = packetsSent;
    this.packetsReceived = packetsReceived;
    this.packetLoss = packetLoss;
    this.rawOutput = rawOutput;
  }

  // Factory method for failed/unreachable hosts
  public static PingResult unreachable(String ip, String rawOutput)
  {
    return new PingResult(ip, false, -1, -1, -1, 1, 0, 100, rawOutput);
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

  public double getMinRtt()
  {
    return minRtt;
  }

  public double getAvgRtt()
  {
    return avgRtt;
  }

  public double getMaxRtt()
  {
    return maxRtt;
  }

  public int getPacketsSent()
  {
    return packetsSent;
  }

  public int getPacketsReceived()
  {
    return packetsReceived;
  }

  public int getPacketLoss()
  {
    return packetLoss;
  }

  public String getRawOutput()
  {
    return rawOutput;
  }

  /**
   * Format result as CSV row: IP,Status,PacketLoss%,MinRTT,AvgRTT,MaxRTT
   */
  public String toCsvRow()
  {
    String status = isAlive ? "UP" : "DOWN";
    if (isAlive)
    {
      return String.format("%s,%s,%d%%,%.2f,%.2f,%.2f",
        ip, status, packetLoss, minRtt, avgRtt, maxRtt);
    }
    else
    {
      return String.format("%s,%s,%d%%,-,-,-",
        ip, status, packetLoss);
    }
  }

  /**
   * Format result in human-readable text format (similar to old ping output)
   */
  public String toFormattedText()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("FPING ").append(ip).append("\n");
    sb.append("Status: ").append(isAlive ? "ALIVE" : "UNREACHABLE").append("\n");
    sb.append("Packets: xmt=").append(packetsSent)
      .append(" rcv=").append(packetsReceived)
      .append(" loss=").append(packetLoss).append("%\n");

    if (isAlive)
    {
      sb.append(String.format("RTT: min=%.2fms avg=%.2fms max=%.2fms\n",
        minRtt, avgRtt, maxRtt));
    }
    else
    {
      sb.append("RTT: N/A (host unreachable)\n");
    }

    return sb.toString();
  }

  @Override
  public String toString()
  {
    return "PingResult{" +
      "ip='" + ip + '\'' +
      ", isAlive=" + isAlive +
      ", avgRtt=" + avgRtt +
      ", packetLoss=" + packetLoss +
      '}';
  }
}
