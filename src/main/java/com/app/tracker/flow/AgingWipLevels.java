package com.app.tracker.flow;

/**
 * Aging WIP (Dalga 2.1) esik hesabi -- saf fonksiyon, hem okuma servisi ({@code AgingWipService})
 * hem de bildirim job'i ({@code AgingWipJob}) tarafindan kullanilir ki iki yer FARKLI sonuc
 * uretmesin (rozet seviyesi ile bildirim seviyesi her zaman ayni tanima dayanmali).
 *
 * <p>En az {@value #MIN_SAMPLE_SIZE} tamamlanmis gorev yoksa p85 istatistiksel olarak anlamsiz
 * kabul edilir, uyari/rozet HIC uretilmez (plan karari).
 */
public final class AgingWipLevels {

  public static final int MIN_SAMPLE_SIZE = 10;

  private AgingWipLevels() {}

  /**
   * @return 0 (normal), 1 (p85'i asmis) veya 2 (2xp85'i asmis)
   */
  public static int levelFor(long ageSeconds, double p85Seconds) {
    if (ageSeconds >= 2 * p85Seconds) {
      return 2;
    }
    if (ageSeconds >= p85Seconds) {
      return 1;
    }
    return 0;
  }
}
