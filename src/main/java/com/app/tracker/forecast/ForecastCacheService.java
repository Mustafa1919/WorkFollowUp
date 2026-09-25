package com.app.tracker.forecast;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Dalga 2.2 (ADR-0013) — projedeki ILK gercek Redis Cache-Aside kullanimi. Spring'in
 * {@code @Cacheable} soyutlamasi yerine DOGRUDAN {@link StringRedisTemplate} tercih edildi: {@code
 * evictProject} bir ANAHTAR PATERNINI (o projeye ait TUM tahminleri, hem proje hem sprint kapsamli)
 * silmek zorunda — {@code @CacheEvict} tek bir anahtari veya bir cache'in TAMAMINI siler, desen
 * eslesmeyi desteklemez.
 *
 * <p>Anahtar semasi PLANDAKI {@code forecast:{ws}:{scope}:{date}} bicimini BILEREK basitlestirir:
 * {@code forecast:{ws}:{projectId}:project} veya {@code
 * forecast:{ws}:{projectId}:sprint:{sprintId}} — tarih segmenti YOK, cunku TTL (1 saat) zaten
 * gunluk tazeligi sagliyor ve {@code evictProject} her Done olayinda ZATEN tam isabetle temizliyor;
 * ayrica projeId'yi anahtara GOMMEK, tek bir pattern silme ile hem proje hem o projenin TUM sprint
 * tahminlerini temizlemeyi mumkun kiliyor.
 *
 * <p>{@code KEYS} komutu (pattern silme) buyuk Redis'lerde bloklayici olabilir; bu olcekte (proje
 * basina en fazla birkac anahtar) kabul edilebilir — cache stampede korumasi PLANIN kendisi gibi
 * Faz 5'e birakildi.
 */
@Service
public class ForecastCacheService {

  private static final Duration TTL = Duration.ofHours(1);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public ForecastCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  public Optional<ForecastResponse> getProject(UUID workspaceId, UUID projectId) {
    return read(projectKey(workspaceId, projectId));
  }

  public void putProject(UUID workspaceId, UUID projectId, ForecastResponse value) {
    write(projectKey(workspaceId, projectId), value);
  }

  public Optional<ForecastResponse> getSprint(UUID workspaceId, UUID projectId, UUID sprintId) {
    return read(sprintKey(workspaceId, projectId, sprintId));
  }

  public void putSprint(UUID workspaceId, UUID projectId, UUID sprintId, ForecastResponse value) {
    write(sprintKey(workspaceId, projectId, sprintId), value);
  }

  /**
   * Bir projede Done gecisi/silme oldugunda cagrilir: o projenin TUM tahmin onbellegini temizler.
   */
  public void evictProject(UUID workspaceId, UUID projectId) {
    Set<String> keys = redisTemplate.keys("forecast:" + workspaceId + ":" + projectId + ":*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }

  private Optional<ForecastResponse> read(String key) {
    String json = redisTemplate.opsForValue().get(key);
    if (json == null) {
      return Optional.empty();
    }
    return Optional.of(objectMapper.readValue(json, ForecastResponse.class));
  }

  private void write(String key, ForecastResponse value) {
    redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
  }

  private static String projectKey(UUID workspaceId, UUID projectId) {
    return "forecast:" + workspaceId + ":" + projectId + ":project";
  }

  private static String sprintKey(UUID workspaceId, UUID projectId, UUID sprintId) {
    return "forecast:" + workspaceId + ":" + projectId + ":sprint:" + sprintId;
  }
}
