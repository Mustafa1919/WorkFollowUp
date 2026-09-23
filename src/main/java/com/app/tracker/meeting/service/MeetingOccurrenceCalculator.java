package com.app.tracker.meeting.service;

import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.model.MeetingFrequency;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Saf, Spring'siz yardimci: {@link Meeting}'in tekrar kurallarindan {@code [from, to]} araligindaki
 * occurrence tarihlerini hesaplar. Occurrence'lar DB'de satir olarak SAKLANMAZ (bkz. Meeting
 * javadoc'u) — bu sinif tek gercek kaynaktir.
 *
 * <p><b>Performans:</b> {@code meeting.startDate}'ten itibaren gun gun ilerlemek yerine, {@code
 * from}'a en yakin hizali occurrence'i ARITMETIKLE bulur (tam sayi bolme) — yillar once baslamis
 * sinirsiz bir gunluk standup icin bile sorgu penceresi (cagiran taraf tipik olarak ≤62 gun ile
 * sinirlar) kadar iterasyon yapar; seriye kac occurrence gectigini saymak icin BASTAN iterasyon
 * YOK.
 *
 * <p><b>Bilinen sinir:</b> MONTHLY'de {@code start.getDayOfMonth()} korunur; hedef ay o gunden
 * kisaysa (orn. 31 Ocak -> Subat) o ay ATLANIR, "ayin son gunu" gibi bir kural desteklenmez.
 */
public final class MeetingOccurrenceCalculator {

  private MeetingOccurrenceCalculator() {}

  public static List<LocalDate> occurrencesInRange(Meeting meeting, LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      return List.of();
    }
    return switch (meeting.getFrequency()) {
      case MeetingFrequency.ONCE -> onceOccurrences(meeting, from, to);
      case MeetingFrequency.DAILY -> dailyOccurrences(meeting, from, to);
      case MeetingFrequency.WEEKLY -> weeklyOccurrences(meeting, from, to);
      case MeetingFrequency.MONTHLY -> monthlyOccurrences(meeting, from, to);
      default -> throw new IllegalStateException("Bilinmeyen frequency: " + meeting.getFrequency());
    };
  }

  /** {@code 'MON,WED,FRI'} -> {@code {MONDAY, WEDNESDAY, FRIDAY}}; bos/null ise bos kume. */
  public static Set<DayOfWeek> parseWeekdays(String csv) {
    if (csv == null || csv.isBlank()) {
      return EnumSet.noneOf(DayOfWeek.class);
    }
    Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
    for (String code : csv.split(",")) {
      days.add(fromCode(code.trim()));
    }
    return days;
  }

  /** {@code 'MON,WED,FRI'} -> {@code ["MON","WED","FRI"]}, kronolojik sırayla (Pazartesi ilk). */
  public static List<String> weekdayCodes(String csv) {
    return parseWeekdays(csv).stream().sorted().map(MeetingOccurrenceCalculator::toCode).toList();
  }

  public static String toWeekdayCsv(Set<DayOfWeek> days) {
    return days.stream()
        .sorted()
        .map(MeetingOccurrenceCalculator::toCode)
        .reduce((a, b) -> a + "," + b)
        .orElse(null);
  }

  private static List<LocalDate> onceOccurrences(Meeting m, LocalDate from, LocalDate to) {
    LocalDate d = m.getStartDate();
    if (d.isBefore(from) || d.isAfter(to)) {
      return List.of();
    }
    return List.of(d);
  }

  private static List<LocalDate> dailyOccurrences(Meeting m, LocalDate from, LocalDate to) {
    LocalDate start = m.getStartDate();
    int interval = Math.max(1, m.getIntervalCount());
    List<LocalDate> result = new ArrayList<>();
    if (to.isBefore(start)) {
      return result;
    }
    long daysFromStart = ChronoUnit.DAYS.between(start, from);
    long firstIndex = daysFromStart <= 0 ? 0 : ceilDiv(daysFromStart, interval);
    LocalDate cursor = start.plusDays(firstIndex * (long) interval);
    long index = firstIndex;
    LocalDate until = m.getUntilDate();
    Integer count = m.getOccurrenceCount();
    while (!cursor.isAfter(to)) {
      if (until != null && cursor.isAfter(until)) {
        break;
      }
      if (count != null && index >= count) {
        break;
      }
      if (!cursor.isBefore(from)) {
        result.add(cursor);
      }
      index++;
      cursor = cursor.plusDays(interval);
    }
    return result;
  }

  private static List<LocalDate> weeklyOccurrences(Meeting m, LocalDate from, LocalDate to) {
    LocalDate start = m.getStartDate();
    int interval = Math.max(1, m.getIntervalCount());
    List<DayOfWeek> days = parseWeekdays(m.getByWeekday()).stream().sorted().toList();
    List<LocalDate> result = new ArrayList<>();
    if (days.isEmpty() || to.isBefore(start)) {
      return result;
    }
    LocalDate until = m.getUntilDate();
    Integer count = m.getOccurrenceCount();

    LocalDate startWeekMonday = mondayOf(start);
    LocalDate targetWeekMonday = mondayOf(from.isBefore(start) ? start : from);
    long weeksFromStart = ChronoUnit.WEEKS.between(startWeekMonday, targetWeekMonday);
    long firstCycle = weeksFromStart <= 0 ? 0 : ceilDiv(weeksFromStart, interval);
    LocalDate cycleMonday = startWeekMonday.plusWeeks(firstCycle * (long) interval);

    // Cycle 0'in ilk haftasi kismi olabilir (start haftanin ortasindaysa, ondan onceki
    // gunler sayilmaz) — count limiti dogru kalsin diye ilk cycle'in GERCEK katki sayisi
    // hesaplanir, sonraki her cycle tam `days.size()` katkida bulunur.
    long index;
    if (firstCycle == 0) {
      index = 0;
    } else {
      long cycle0Contribution =
          days.stream().filter(d -> !weekday(startWeekMonday, d).isBefore(start)).count();
      index = cycle0Contribution + (firstCycle - 1) * days.size();
    }

    while (!cycleMonday.isAfter(to)) {
      for (DayOfWeek dow : days) {
        LocalDate d = weekday(cycleMonday, dow);
        if (d.isBefore(start)) {
          continue;
        }
        if (until != null && d.isAfter(until)) {
          return result;
        }
        if (count != null && index >= count) {
          return result;
        }
        if (!d.isBefore(from) && !d.isAfter(to)) {
          result.add(d);
        }
        index++;
      }
      cycleMonday = cycleMonday.plusWeeks(interval);
    }
    return result;
  }

  private static List<LocalDate> monthlyOccurrences(Meeting m, LocalDate from, LocalDate to) {
    LocalDate start = m.getStartDate();
    int interval = Math.max(1, m.getIntervalCount());
    int dayOfMonth = start.getDayOfMonth();
    List<LocalDate> result = new ArrayList<>();
    if (to.isBefore(start)) {
      return result;
    }
    LocalDate until = m.getUntilDate();
    Integer count = m.getOccurrenceCount();

    YearMonth startMonth = YearMonth.from(start);
    YearMonth targetMonth = YearMonth.from(from.isBefore(start) ? start : from);
    long monthsFromStart = startMonth.until(targetMonth, ChronoUnit.MONTHS);
    long firstIndex = monthsFromStart <= 0 ? 0 : ceilDiv(monthsFromStart, interval);
    long index = firstIndex;
    YearMonth ym = startMonth.plusMonths(firstIndex * interval);

    while (!ym.atDay(1).isAfter(to)) {
      if (dayOfMonth <= ym.lengthOfMonth()) {
        LocalDate cursor = ym.atDay(dayOfMonth);
        if (!cursor.isBefore(start)) {
          if (until != null && cursor.isAfter(until)) {
            break;
          }
          if (count != null && index >= count) {
            break;
          }
          if (!cursor.isBefore(from) && !cursor.isAfter(to)) {
            result.add(cursor);
          }
          index++;
        }
      }
      ym = ym.plusMonths(interval);
    }
    return result;
  }

  private static LocalDate mondayOf(LocalDate date) {
    return date.minusDays(date.getDayOfWeek().getValue() - 1L);
  }

  private static LocalDate weekday(LocalDate monday, DayOfWeek dow) {
    return monday.plusDays(dow.getValue() - 1L);
  }

  private static long ceilDiv(long a, int b) {
    return (a + b - 1) / b;
  }

  private static DayOfWeek fromCode(String code) {
    return switch (code) {
      case "MON" -> DayOfWeek.MONDAY;
      case "TUE" -> DayOfWeek.TUESDAY;
      case "WED" -> DayOfWeek.WEDNESDAY;
      case "THU" -> DayOfWeek.THURSDAY;
      case "FRI" -> DayOfWeek.FRIDAY;
      case "SAT" -> DayOfWeek.SATURDAY;
      case "SUN" -> DayOfWeek.SUNDAY;
      default -> throw new IllegalArgumentException("Gecersiz gun kodu: " + code);
    };
  }

  private static String toCode(DayOfWeek dow) {
    return Arrays.asList("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").get(dow.getValue() - 1);
  }
}
