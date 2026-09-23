package com.app.tracker.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.model.MeetingFrequency;
import com.app.tracker.meeting.service.MeetingOccurrenceCalculator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spring context'siz saf unit test — {@link MeetingOccurrenceCalculator} sadece {@code java.time}
 * kullanır. Tarihler bilerek sabit (Pazartesi başlangıçlı bilinen bir hafta) — kenar durumları elle
 * doğrulanabilsin diye.
 */
class MeetingOccurrenceCalculatorTest {

  // 2026-09-21 bir Pazartesi.
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

  @Test
  void onceOnlyOccursOnItsDate() {
    Meeting m = meeting(MeetingFrequency.ONCE, MONDAY, 1, null, null, null);
    assertEquals(
        List.of(MONDAY),
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY, MONDAY.plusDays(30)));
    assertEquals(
        List.of(),
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY.plusDays(1), MONDAY.plusDays(30)));
  }

  @Test
  void dailyEveryDayInRange() {
    Meeting m = meeting(MeetingFrequency.DAILY, MONDAY, 1, null, null, null);
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY.plusDays(5), MONDAY.plusDays(9));
    assertEquals(5, occ.size());
    assertEquals(MONDAY.plusDays(5), occ.get(0));
    assertEquals(MONDAY.plusDays(9), occ.get(4));
  }

  @Test
  void dailyIntervalAlignsFarFromStart() {
    // interval=3: MONDAY, MONDAY+3, MONDAY+6, ... — pencere seriden cok sonra baslasa bile hizali
    // kalmali.
    Meeting m = meeting(MeetingFrequency.DAILY, MONDAY, 3, null, null, null);
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(
            m, MONDAY.plusDays(100), MONDAY.plusDays(110));
    for (LocalDate d : occ) {
      long offset = java.time.temporal.ChronoUnit.DAYS.between(MONDAY, d);
      assertEquals(0, offset % 3, "interval'e hizali olmali: " + d);
    }
    assertTrue(occ.size() >= 3);
  }

  @Test
  void dailyCountLimitsTotalOccurrencesRegardlessOfWindow() {
    Meeting m = meeting(MeetingFrequency.DAILY, MONDAY, 1, null, 3, null);
    // count=3 -> yalniz MONDAY, MONDAY+1, MONDAY+2. Uzak bir pencere sorulsa da bos donmeli.
    assertEquals(
        3, MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY, MONDAY.plusDays(60)).size());
    assertEquals(
        List.of(),
        MeetingOccurrenceCalculator.occurrencesInRange(
            m, MONDAY.plusDays(10), MONDAY.plusDays(20)));
  }

  @Test
  void weeklyOnSelectedDays() {
    Meeting m = meeting(MeetingFrequency.WEEKLY, MONDAY, 1, "MON,WED", null, null);
    // Bir haftalik pencere: Pazartesi + Carsamba.
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY, MONDAY.plusDays(6));
    assertEquals(List.of(MONDAY, MONDAY.plusDays(2)), occ);
  }

  @Test
  void weeklyBiweeklySkipsOffWeeks() {
    Meeting m = meeting(MeetingFrequency.WEEKLY, MONDAY, 2, "MON", null, null);
    // Iki haftada bir: MONDAY, MONDAY+14, MONDAY+28 — MONDAY+7/+21 (ara haftalar) gelmemeli.
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY, MONDAY.plusDays(35));
    assertEquals(List.of(MONDAY, MONDAY.plusDays(14), MONDAY.plusDays(28)), occ);
  }

  @Test
  void weeklyCountLimitIsCorrectWhenStartingMidWeek() {
    // Start bir Carsamba (haftanin ortasi); byWeekday={MON,WED,FRI}. Ilk hafta yalniz Carsamba VE
    // Cuma sayilir (Pazartesi start'tan ONCE oldugu icin dahil degil) — index hizalamasi bunu
    // hesaba katmali (bkz. MeetingOccurrenceCalculator#weeklyOccurrences javadoc'u).
    LocalDate wednesday = MONDAY.plusDays(2);
    Meeting m = meeting(MeetingFrequency.WEEKLY, wednesday, 1, "MON,WED,FRI", 4, null);
    // count=4: Carsamba(0), Cuma(1), sonraki hafta Pazartesi(2), Carsamba(3) — Cuma(4) DAHIL DEGIL.
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, wednesday, wednesday.plusDays(20));
    assertEquals(
        List.of(wednesday, wednesday.plusDays(2), wednesday.plusDays(5), wednesday.plusDays(7)),
        occ);
  }

  @Test
  void monthlySkipsShortMonths() {
    // 31 Ocak -> Subat (28/29 gun) ATLANIR, Mart 31'de tekrar gelir.
    LocalDate jan31 = LocalDate.of(2027, 1, 31);
    Meeting m = meeting(MeetingFrequency.MONTHLY, jan31, 1, null, null, null);
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, jan31, LocalDate.of(2027, 3, 31));
    assertEquals(List.of(jan31, LocalDate.of(2027, 3, 31)), occ);
  }

  @Test
  void untilDateCutsSeriesOff() {
    Meeting m = meeting(MeetingFrequency.DAILY, MONDAY, 1, null, null, MONDAY.plusDays(2));
    List<LocalDate> occ =
        MeetingOccurrenceCalculator.occurrencesInRange(m, MONDAY, MONDAY.plusDays(10));
    assertEquals(List.of(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2)), occ);
  }

  @Test
  void rangeBeforeSeriesStartIsEmpty() {
    Meeting m = meeting(MeetingFrequency.DAILY, MONDAY, 1, null, null, null);
    assertEquals(
        List.of(),
        MeetingOccurrenceCalculator.occurrencesInRange(
            m, MONDAY.minusDays(10), MONDAY.minusDays(1)));
  }

  private static Meeting meeting(
      String frequency,
      LocalDate startDate,
      int interval,
      String byWeekday,
      Integer count,
      LocalDate until) {
    return Meeting.of(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "T",
        null,
        null,
        startDate,
        LocalTime.of(10, 0),
        30,
        frequency,
        interval,
        byWeekday,
        until,
        count,
        null,
        UUID.randomUUID());
  }
}
