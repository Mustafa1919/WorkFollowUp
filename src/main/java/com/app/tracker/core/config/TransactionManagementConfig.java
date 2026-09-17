package com.app.tracker.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Boot'un varsayilan transaction advisor'i {@code Ordered.LOWEST_PRECEDENCE} ile calisir —
 * bu, {@link com.app.tracker.core.tenancy.TenancyGuardAspect} ile ayni degerde oldugundan
 * hangisinin daha "disarida" (once) calisacagi garanti degildir. Transaction'in ONCE baslamasi,
 * tenancy aspect'inin ISE transaction icinde (SONRA) calismasi zorunlu oldugundan (bkz.
 * PHASE_1_DETAILED_DESIGN Bolum 3.1, Kural 1) burada acikca daha yuksek onceliğe (daha kucuk order)
 * sahip bir transaction advisor tanimlaniyor.
 */
@Configuration
@EnableTransactionManagement(order = TransactionManagementConfig.ORDER)
public class TransactionManagementConfig {

  public static final int ORDER = 0;
}
