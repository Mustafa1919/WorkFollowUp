package com.app.tracker.meeting.model;

/**
 * V20__meetings.sql {@code frequency} kolonu icin izinli degerler (TaskStatus/SprintStatus ile AYNI
 * desen — kod tabaninda bu tur kapali deger kumeleri JPA {@code @Enumerated} yerine duz String
 * sabitleriyle tutulur).
 */
public final class MeetingFrequency {

  private MeetingFrequency() {}

  public static final String ONCE = "ONCE";
  public static final String DAILY = "DAILY";
  public static final String WEEKLY = "WEEKLY";
  public static final String MONTHLY = "MONTHLY";
}
