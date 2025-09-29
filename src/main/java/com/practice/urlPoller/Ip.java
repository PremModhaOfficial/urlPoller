package com.practice.urlPoller;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty; // Optional: for explicit JSON field names

public class Ip implements Comparable<Ip>, Serializable
{
  private String address;
  private byte poll_time;

  public Ip(String url)
  {
    var url_time = url.split(",");

    address = url_time[0].strip();
    poll_time = Byte.parseByte(url_time[1].strip());
  }

  // Add getters for Jackson serialization
  @JsonProperty("address") // Optional: customize JSON key
  public String getAddress()
  {
    return address;
  }

  @JsonProperty("poll_time") // Optional: customize JSON key
  public byte getPollTime()
  {
    return poll_time;
  }

  @Override
  public int compareTo(Ip o)
  {
    return Byte.compare(this.poll_time, o.poll_time);
  }

  @Override
  public String toString()
  {
    return "Ip{address=" + address + ", poll_time=" + poll_time + "}";
  }


}
