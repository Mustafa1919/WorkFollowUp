package com.app.tracker.integration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.integration.repository.WebhookIntegrationLookupRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Sicak yol cache'i: sinirli, YALNIZ-pozitif, TTL'li, acik evict. DB/Spring yok (sahte lookup). */
class WebhookIntegrationResolverTest {

  private static final long TTL_NANOS = 60_000_000_000L;

  /** DB'ye hic gitmeyen sahte lookup; cagri sayisi cache'in isabetini olcer. */
  private static final class FakeLookup extends WebhookIntegrationLookupRepository {
    final Map<UUID, Resolved> rows = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();

    FakeLookup() {
      super(null);
    }

    @Override
    public Optional<Resolved> resolve(UUID integrationId) {
      calls.incrementAndGet();
      return Optional.ofNullable(rows.get(integrationId));
    }
  }

  private final FakeLookup lookup = new FakeLookup();
  private final AtomicLong clock = new AtomicLong(1_000);
  private final WebhookIntegrationResolver resolver =
      new WebhookIntegrationResolver(lookup, TTL_NANOS, clock::get);

  private UUID register(int secretVersion) {
    UUID id = UUID.randomUUID();
    lookup.rows.put(
        id, new WebhookIntegrationLookupRepository.Resolved(id, UUID.randomUUID(), secretVersion));
    return id;
  }

  @Test
  void foundIntegrationIsServedFromCacheUntilTtlExpires() {
    UUID id = register(1);

    resolver.resolve(id);
    resolver.resolve(id);
    assertEquals(1, lookup.calls.get(), "ikinci cagri DB'ye gitmemeli");

    clock.addAndGet(TTL_NANOS + 1);
    resolver.resolve(id);
    assertEquals(2, lookup.calls.get(), "TTL dolunca yeniden okunmali");
  }

  @Test
  void unknownIntegrationsAreNeverCached() {
    UUID unknown = UUID.randomUUID();

    assertTrue(resolver.resolve(unknown).isEmpty());
    assertTrue(resolver.resolve(unknown).isEmpty());

    assertEquals(2, lookup.calls.get(), "rastgele id gonderen cache'i dolduramamali");
  }

  @Test
  void evictForcesFreshLookupSoRotationTakesEffect() {
    UUID id = register(1);
    assertEquals(1, resolver.resolve(id).orElseThrow().secretVersion());

    lookup.rows.put(id, new WebhookIntegrationLookupRepository.Resolved(id, UUID.randomUUID(), 2));
    assertEquals(
        1, resolver.resolve(id).orElseThrow().secretVersion(), "evict'ten once eski deger");

    resolver.evict(id);
    assertEquals(2, resolver.resolve(id).orElseThrow().secretVersion());
  }

  @Test
  void deletedIntegrationStopsResolvingAfterTtlWithoutEvict() {
    UUID id = register(1);
    resolver.resolve(id);
    lookup.rows.remove(id);

    clock.addAndGet(TTL_NANOS + 1);

    assertTrue(resolver.resolve(id).isEmpty());
  }
}
