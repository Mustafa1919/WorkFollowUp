package com.app.tracker.automation;

/**
 * Dalga 3.1 (ADR-0016) — sabit otomasyon sablonlari. Serbest kural editoru bilerek YOK; her
 * sablonun tetikleyicisi ve etkisi kod icinde sabittir, workspace/proje bazinda yalniz ac/kapa
 * (bkz. {@code automation_rules.enabled}).
 *
 * <p>{@link #PR_MERGE_TO_DONE} tek istisnadir: varsayilan olarak ACIKTIR (opt-out) — {@code
 * GithubEventProcessor}'in V13'ten beri var olan mevcut davranisini korur, satir hic olusturulmamis
 * eski projelerde davranis SESSIZCE degismesin diye. Diger dort sablon varsayilan KAPALIDIR
 * (opt-in) — yeni davranis, kullanici acikca acmadan calismamali.
 */
public enum AutomationTemplateKey {
  /**
   * PR merge (closed+merged) edilince gorev Done'a geçer — mevcut GithubEventProcessor davranisi,
   * buradan ac/kapa edilebilir.
   */
  PR_MERGE_TO_DONE,
  /** Bir parent'in TUM alt gorevleri Done olunca parent Review'a geçer. */
  SUBTASK_ALL_DONE_PARENT_TO_REVIEW,
  /**
   * Bir gorevi bloklayan gorev Done olunca, bloklanan gorevin atanani+izleyicileri bilgilendirilir.
   */
  BLOCKER_DONE_NOTIFY,
  /** Bitis tarihi gecmis, Done olmayan gorevler icin gunde bir kez atanan+izleyicilere bildirim. */
  OVERDUE_NOTIFY,
  /** Bir gorev To Do durumundayken birine atanirsa otomatik In Progress'e geçer. */
  ASSIGNED_TODO_TO_IN_PROGRESS
}
