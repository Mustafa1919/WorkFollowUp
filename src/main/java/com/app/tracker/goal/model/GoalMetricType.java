package com.app.tracker.goal.model;

/**
 * Bir donem hedefinin ilerlemesinin NASIL olculdugu (V21__goals.sql).
 *
 * <p>{@link #COMPLETED_TASKS} ve {@link #COMPLETED_POINTS} OTOMATIK metriklerdir: ilerleme, raporun
 * gecmise donuk tarafiyla AYNI kaynaktan ({@code task_analytics} read model'i) hesaplanir, yani
 * hedef ilerlemesi ile rapordaki sayi hicbir zaman celismez. {@link #CUSTOM} ise sayisallastirmasi
 * urun tarafinda olmayan hedefler icindir (orn. "3 musteri demosu yap"); degeri elle girilir.
 */
public enum GoalMetricType {

  /** Donemde tamamlanan (son Done gecisi donem icinde kalan) gorev sayisi. */
  COMPLETED_TASKS,

  /**
   * Ayni gorevlerin story point toplami; puansiz gorev 0 sayilir (SprintAnalytics ile ayni kural).
   */
  COMPLETED_POINTS,

  /** Elle guncellenen serbest sayac. */
  CUSTOM
}
