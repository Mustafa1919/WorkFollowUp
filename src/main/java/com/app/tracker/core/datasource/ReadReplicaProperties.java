package com.app.tracker.core.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.datasource.read.*} — read DataSource. {@code url} bos ise AYRI havuz kurulmaz, okuma
 * yolu da yazma havuzunu kullanir (yerel gelistirme ve gercek replica olmayan ortamlar). {@code
 * username}/{@code password} bos ise yazma DataSource'unun kimligi kullanilir. Tenant izolasyonu
 * (RLS) replica'da da gecerlidir: politikalar replike edilir, {@code app_runtime} rolu ayni kalir.
 *
 * <p>Ilk asamada url ayni veritabanina bakabilir; gercek streaming replica Faz4/5 "infra
 * derinlesme" sprintinde (CloudNativePG) baglanacak — o gun degisecek tek sey bu url'dir.
 */
@ConfigurationProperties("app.datasource.read")
public record ReadReplicaProperties(
    String url, String username, String password, Integer maximumPoolSize) {

  public boolean isConfigured() {
    return url != null && !url.isBlank();
  }
}
