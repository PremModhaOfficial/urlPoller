package com.practice.urlPoller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized logging configuration for URL Poller.
 * Manages IP whitelisting for detailed per-IP logging and batch ID generation.
 */
public class LogConfig
{
  private static final Logger logger = LoggerFactory.getLogger(LogConfig.class);

  // IP whitelist for per-IP detailed logging (TRACE level)
  private static final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();

  // Enable/disable per-IP logging globally
  private static final boolean perIpLoggingEnabled = true;

  /**
   * Load whitelist from comma-separated string.
   * Empty string means log all IPs (development mode).
   */
  public static void loadWhitelist(String csvIps)
  {
    if (csvIps != null && !csvIps.isBlank())
    {
      for (var ip : csvIps.split(","))
      {
        whitelistedIps.add(ip.strip());
      }
      logger.info("Loaded {} IPs to whitelist", whitelistedIps.size());
    } else
    {
      logger.info("No whitelist specified - logging all IPs (development mode)");
    }
  }

  /**
   * Check if detailed logging should be enabled for this IP.
   * Returns true if IP is whitelisted OR whitelist is empty (development mode).
   */
  public static boolean shouldLogIp(String ip)
  {
    // If whitelist is empty, log all IPs (development mode)
    if (whitelistedIps.isEmpty()) return perIpLoggingEnabled;
    return whitelistedIps.contains(ip);
  }

  /**
   * Load whitelist from file (one IP per line, comments with #).
   */
  public static void loadWhitelistFromFile(String filePath)
  {
    try
    {
      var path = java.nio.file.Paths.get(filePath);
      var lines = java.nio.file.Files.readAllLines(path);

      for (var line : lines)
      {
        var trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
        {
          whitelistedIps.add(trimmed);
        }
      }
      logger.info("Loaded {} IPs from whitelist file: {}", whitelistedIps.size(), filePath);
    } catch (java.io.IOException e)
    {
      logger.error("Failed to load whitelist from file: {}", filePath, e);
    }
  }

  /**
   * Get current whitelist size.
   */
  public static int getWhitelistSize()
  {
    return whitelistedIps.size();
  }

}
