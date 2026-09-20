package com.app.tracker.core.datasource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1 (CQRS) — isaretli metot/sinifin {@code readOnly = true}
 * transaction'lari read DataSource'a yonlenir. Yonlendirme OPT-IN'dir, {@code readOnly} tek basina
 * yetmez: cekirdek API'nin okuma endpoint'leri (liste, detay) yazma sonrasi "kendi yazdigini oku"
 * bekler; gercek bir replica'da replikasyon gecikmesi bunlari bozardi. Yalnizca gecikmeye
 * TOLERANSLI okumalar (analitik dashboard) isaretlenmelidir.
 *
 * <p>Guvenlik agi: transaction {@code readOnly} degilse isaret yok sayilir ve yazma DataSource'u
 * kullanilir (bkz. {@link RoutingDataSourceConfig}).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReadReplica {}
