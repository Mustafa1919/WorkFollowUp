package com.app.tracker.core.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Tek bir STOMP oturumunun teslim anindaki denetim durumu (bkz. {@link WebSocketDeliveryGuard}).
 * clientOutboundChannel birden cok thread'de kostugu icin tum degisken alanlar atomik/eszamanli
 * yapilardir.
 *
 * <p>Workspace basina uyelik dogrulamasi {@link #verifyMembership} ile yapilir: araligi dolmus bir
 * kontrolu ayni anda yalniz bir thread sorgular, eszamanli teslimatlar onun SONUCUNU bekler;
 * boylece sirali mesajlarda oturum ve workspace basina aralik icinde en cok bir DB sorgusu yapilir
 * ve suren (henuz sonuclanmamis) bir kontrol hicbir teslimati gecirmez. Blacklist burada
 * onbelleklenmez (her mesajda sorulur, bkz. WebSocketDeliveryGuard).
 */
final class WebSocketSessionState {

  private final UUID userId;
  private final String jti;
  private final Instant expiresAt;

  /** Suren bir uyelik sorgusunun sonucunu bekleme ust siniri; asilirsa mesaj dusurulur. */
  private static final Duration PENDING_WAIT = Duration.ofSeconds(10);

  private final Map<UUID, MembershipCheck> membershipChecks = new ConcurrentHashMap<>();
  private final AtomicBoolean closing = new AtomicBoolean(false);

  WebSocketSessionState(UUID userId, String jti, Instant expiresAt) {
    this.userId = userId;
    this.jti = jti;
    this.expiresAt = expiresAt;
  }

  UUID userId() {
    return userId;
  }

  String jti() {
    return jti;
  }

  Instant expiresAt() {
    return expiresAt;
  }

  boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /**
   * Workspace uyeligini aralik kuraliyla dogrular; {@code query} gercek (DB) sorgusudur.
   *
   * <ul>
   *   <li>Son BASARILI dogrulama {@code interval}'den taze ise sorgu yapilmaz, {@code true}.
   *   <li>Degilse ve suren bir sorgu yoksa bu thread sorguyu sahiplenir ve yapar.
   *   <li>Suren bir sorgu varsa onun SONUCU beklenir; eski dogrulama gecerli SAYILMAZ
   *       (fail-closed).
   * </ul>
   *
   * <p>"Dogrulandi" ani yalniz sorgu basariyla {@code true} donunce yazilir (sorgunun basladigi an;
   * muhafazakar). Sorgu hata verirse sahiplenme birakilir, bekleyenler de hata alir; cagiran mesaji
   * dusurur.
   */
  boolean verifyMembership(
      UUID workspaceId, Instant now, Duration interval, BooleanSupplier query) {
    CompletableFuture<Boolean> mine = new CompletableFuture<>();
    MembershipCheck current =
        membershipChecks.compute(
            workspaceId,
            (id, cur) -> {
              if (cur != null && (cur.pending() != null || cur.isFresh(now, interval))) {
                return cur;
              }
              return new MembershipCheck(cur == null ? null : cur.verifiedAt(), mine);
            });
    if (current.pending() == mine) {
      return runOwnedQuery(workspaceId, now, mine, query);
    }
    if (current.pending() != null) {
      return awaitPending(current.pending());
    }
    return true;
  }

  private boolean runOwnedQuery(
      UUID workspaceId, Instant now, CompletableFuture<Boolean> mine, BooleanSupplier query) {
    boolean member;
    try {
      member = query.getAsBoolean();
    } catch (RuntimeException e) {
      membershipChecks.computeIfPresent(
          workspaceId,
          (id, cur) -> cur.pending() == mine ? new MembershipCheck(cur.verifiedAt(), null) : cur);
      mine.completeExceptionally(e);
      throw e;
    }
    membershipChecks.computeIfPresent(
        workspaceId,
        (id, cur) ->
            cur.pending() == mine
                ? new MembershipCheck(member ? now : cur.verifiedAt(), null)
                : cur);
    mine.complete(member);
    return member;
  }

  private static boolean awaitPending(CompletableFuture<Boolean> pending) {
    try {
      return pending.get(PENDING_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Uyelik dogrulamasi beklenirken kesildi.", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Suren uyelik dogrulamasi sonuclanmadi.", e);
    }
  }

  /** SUBSCRIBE'da yapilan basarili uyelik kontrolunu kaydeder; suren bir sorguya dokunmaz. */
  void recordMembershipCheck(UUID workspaceId, Instant now) {
    membershipChecks.compute(
        workspaceId,
        (id, cur) -> cur != null && cur.pending() != null ? cur : new MembershipCheck(now, null));
  }

  /**
   * Workspace basina uyelik dogrulama durumu: son basarili dogrulama ani (yoksa {@code null}) ve
   * varsa suren sorgunun sonucu.
   */
  private record MembershipCheck(Instant verifiedAt, CompletableFuture<Boolean> pending) {
    boolean isFresh(Instant now, Duration interval) {
      return verifiedAt != null && now.isBefore(verifiedAt.plus(interval));
    }
  }

  /** Ilk cagrida true (ERROR uretilecek), sonrakilerde false (mesaj sessizce dusurulur). */
  boolean markClosing() {
    return closing.compareAndSet(false, true);
  }

  boolean isClosing() {
    return closing.get();
  }
}
