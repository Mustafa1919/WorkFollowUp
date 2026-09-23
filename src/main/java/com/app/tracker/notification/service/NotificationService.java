package com.app.tracker.notification.service;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * REST tarafi: bir kullanicinin KENDI bildirimleri (bkz. NotificationRepository javadoc'u — RLS
 * yalniz workspace izolasyonu saglar, {@code userId = CurrentUser.id()} filtresi HER metotta ACIKCA
 * verilir). Satir yazan taraf {@link InboxFanoutService}'tir (Kafka consumer'i).
 */
@Service
public class NotificationService {

  private static final int MAX_PAGE_SIZE = 100;

  private final NotificationRepository notificationRepository;

  public NotificationService(NotificationRepository notificationRepository) {
    this.notificationRepository = notificationRepository;
  }

  @Transactional(readOnly = true)
  public PageResponse<Notification> listMine(
      UUID userId, int limit, String cursor, boolean unreadOnly) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    Pageable pageable = PageRequest.of(0, boundedLimit + 1);
    List<Notification> rows =
        (cursor == null || cursor.isBlank())
            ? notificationRepository.findFirstPage(userId, unreadOnly, pageable)
            : queryFromCursor(userId, unreadOnly, cursor, pageable);

    boolean hasMore = rows.size() > boundedLimit;
    List<Notification> page = hasMore ? rows.subList(0, boundedLimit) : rows;
    String nextCursor =
        hasMore
            ? new NotificationCursor(
                    page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                .encode()
            : null;
    return new PageResponse<>(page, nextCursor, hasMore);
  }

  @Transactional(readOnly = true)
  public long unreadCount(UUID userId) {
    return notificationRepository.countByUserIdAndReadAtIsNull(userId);
  }

  /**
   * Idempotent: zaten okunmus bildirimi tekrar okundu isaretlemek hata degil no-op'tur. Baska
   * kullanicinin bildirimi (id RLS'ten gecse bile, ayni workspace'te olabilir) 404 doner — varlik
   * sizintisi degil, ownership kontrolu.
   */
  @Transactional
  public Notification markRead(UUID id, UUID userId) {
    Notification notification =
        notificationRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Bildirim bulunamadi."));
    if (notification.isUnread()) {
      notification.markRead(Instant.now());
      notificationRepository.save(notification);
    }
    return notification;
  }

  @Transactional
  public void markAllRead(UUID userId) {
    notificationRepository.markAllRead(userId, Instant.now());
  }

  private List<Notification> queryFromCursor(
      UUID userId, boolean unreadOnly, String cursor, Pageable pageable) {
    NotificationCursor decoded = NotificationCursor.decode(cursor);
    return notificationRepository.findNextPage(
        userId, unreadOnly, decoded.createdAt(), decoded.id(), pageable);
  }
}
