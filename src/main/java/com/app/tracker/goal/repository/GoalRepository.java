package com.app.tracker.goal.repository;

import com.app.tracker.goal.model.Goal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Hedefler RLS'e tabidir; workspace suzgeci sorgularda YAZILMAZ (V21 politikasi uygular).
 *
 * <p>Yillik ve ceyreklik icin AYRI metot var: {@code periodQuarter} nullable oldugu icin tek bir
 * turetilmis sorguyla ("= :quarter" ile null) ifade edilemez — JPQL'de {@code = NULL} hicbir satiri
 * eslemez, sessizce bos liste doner.
 */
public interface GoalRepository extends JpaRepository<Goal, UUID> {

  List<Goal> findByPeriodYearAndPeriodQuarterIsNullOrderByCreatedAtAsc(short periodYear);

  List<Goal> findByPeriodYearAndPeriodQuarterOrderByCreatedAtAsc(short periodYear, short quarter);
}
