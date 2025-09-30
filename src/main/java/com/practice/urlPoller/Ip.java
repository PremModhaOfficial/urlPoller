package com.practice.urlPoller;

public class Ip
{
  public static final String COMMA = ",";
  private final String address;
  private final byte pollTime;

  public Ip(String url)
  {
    var url_time = url.split(COMMA);

    address = url_time[0].strip();
    pollTime = Byte.parseByte(url_time[1].strip());
  }

  // Add getters for Jackson serialization
  public String getAddress()
  {
    return address;
  }

  public byte getPollTime()
  {
    return pollTime;
  }


  @Override
  public String toString()
  {
    return "Ip{address=" + address + ", poll_time=" + pollTime + "}";
  }


}
