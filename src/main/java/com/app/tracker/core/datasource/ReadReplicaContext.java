package com.app.tracker.core.datasource;

/**
 * "Bu thread'deki okuma read DataSource'a gidebilir" bayragi. {@link ReadReplicaAspect} kurar;
 * yonlendirici ({@link RoutingDataSourceConfig}) okur. Ic ice cagrilarda dis kapsamin degeri
 * korunur.
 */
final class ReadReplicaContext {

  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private ReadReplicaContext() {}

  static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  /**
   * @return onceki deger; {@link #restore} ile geri verilir.
   */
  static Boolean activate() {
    Boolean previous = ACTIVE.get();
    ACTIVE.set(Boolean.TRUE);
    return previous;
  }

  static void restore(Boolean previous) {
    if (previous == null) {
      ACTIVE.remove();
    } else {
      ACTIVE.set(previous);
    }
  }
}
