package com.app.tracker.integration.service;

import com.app.tracker.integration.WebhookProperties;
import com.app.tracker.integration.repository.WebhookIntegrationLookupRepository;
import com.app.tracker.integration.repository.WebhookIntegrationLookupRepository.Resolved;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Ingestion sicak yolunun DB'ye bagimliligini azaltan kucuk, sinirli, YALNIZ-POZITIF cache.
 * Gerekce: webhook burst'unda her istek icin bir DB sorgusu, cekirdek API ile PAYLASILAN kucuk
 * baglanti havuzunu tuketirdi (backend-notlari: "paylasilan sinirli kaynak"); ayni JVM'de kalmanin
 * bedeli bu izolasyonu kodla kurmaktir.
 *
 * <p>Yalniz bulunan entegrasyonlar cache'lenir: rastgele UUID gonderen bir saldirgan cache'i
 * dolduramaz (bulunmayanlar her seferinde PK sorgusuna duser; kaba kuvvet korumasi Faz 4 gateway
 * rate limit'inin isidir). Boyut siniri asilirsa cache tamamen bosaltilir (LRU'nun karmasikligina
 * degmez).
 *
 * <p>Tutarlilik bedeli: rotate/silme islemleri BU pod'un cache'ini commit sonrasi hemen temizler,
 * ama diger pod'lar TTL (varsayilan 60 sn) kadar eski secret surumunu kabul edebilir.
 */
@Component
@Profile("!migrate")
public class WebhookIntegrationResolver {

  static final int MAX_ENTRIES = 10_000;

  private record CacheEntry(Resolved resolved, long expiresAtNanos) {}

  private final WebhookIntegrationLookupRepository lookupRepository;
  private final long ttlNanos;
  private final LongSupplier nanoClock;
  private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

  @Autowired
  public WebhookIntegrationResolver(
      WebhookIntegrationLookupRepository lookupRepository, WebhookProperties properties) {
    this(lookupRepository, properties.getIntegrationCacheTtl().toNanos(), System::nanoTime);
  }

  WebhookIntegrationResolver(
      WebhookIntegrationLookupRepository lookupRepository, long ttlNanos, LongSupplier nanoClock) {
    this.lookupRepository = lookupRepository;
    this.ttlNanos = ttlNanos;
    this.nanoClock = nanoClock;
  }

  public Optional<Resolved> resolve(UUID integrationId) {
    long now = nanoClock.getAsLong();
    CacheEntry cached = cache.get(integrationId);
    if (cached != null && now - cached.expiresAtNanos() < 0) {
      return Optional.of(cached.resolved());
    }
    Optional<Resolved> found = lookupRepository.resolve(integrationId);
    if (found.isPresent()) {
      if (cache.size() >= MAX_ENTRIES) {
        cache.clear();
      }
      cache.put(integrationId, new CacheEntry(found.get(), now + ttlNanos));
    } else {
      cache.remove(integrationId);
    }
    return found;
  }

  public void evict(UUID integrationId) {
    cache.remove(integrationId);
  }
}
