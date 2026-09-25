package com.app.tracker.meeting.repository;

import com.app.tracker.meeting.model.Meeting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

  List<Meeting> findAllByOrderByStartDateAscStartTimeAsc();

  /**
   * {@link com.app.tracker.meeting.service.MeetingReminderJob} icin — yalniz hatirlatmasi olanlar.
   */
  List<Meeting> findAllByReminderMinutesBeforeIsNotNull();

  /** {@code StandupDigestJob} icin — yalniz standup'i acik olanlar (Dalga 2.3). */
  List<Meeting> findAllByStandupEnabledTrue();
}
