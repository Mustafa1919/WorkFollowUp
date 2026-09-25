package com.app.tracker.retro;

import com.app.tracker.retro.RetroRepository.CycleTimeOutlier;
import com.app.tracker.retro.RetroRepository.LongestOpenBlocker;
import java.util.List;

/**
 * @param planAvailable false: bu sprint V30'dan ONCE tamamlandigi icin "sprint basi" kesiti hic
 *     hesaplanmadi (bkz. ADR-0015) — {@code committedAtStart*}, {@code addedTasks}, {@code
 *     removedTasks} bu durumda anlamsizdir.
 * @param forecastProbabilityAtStart sprint BASLARKEN o ana kadarki gercek throughput'la Monte
 *     Carlo'nun hesaplayacagi "zamaninda bitme olasiligi" (geriye donuk, ADR-0013'un AYNI
 *     fonksiyonuyla); az veri varsa {@code null}.
 */
public record RetroResponse(
    boolean planAvailable,
    Integer committedAtStartTasks,
    Long committedAtStartPoints,
    int committedTasks,
    long committedPoints,
    int completedTasks,
    long completedPoints,
    List<RetroTaskRef> addedTasks,
    List<RetroTaskRef> removedTasks,
    List<RetroTaskRef> spilloverTasks,
    List<CycleTimeOutlier> cycleTimeOutliers,
    LongestOpenBlocker longestOpenBlocker,
    Double forecastProbabilityAtStart) {

  public RetroResponse {
    addedTasks = List.copyOf(addedTasks);
    removedTasks = List.copyOf(removedTasks);
    spilloverTasks = List.copyOf(spilloverTasks);
    cycleTimeOutliers = List.copyOf(cycleTimeOutliers);
  }
}
