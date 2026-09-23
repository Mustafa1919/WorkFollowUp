package com.app.tracker.notification.repository;

import com.app.tracker.notification.model.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * TaskRepository (PHASE_1 Bolum 4.1) ile AYNI keyset (cursor) pagination deseni: {@code
 * (created_at, id) < (:cursor, :cursorId)} lexicographic karsilastirmasi. "Yalniz benim" kurali
 * (user_id = :userId) HER sorguda ACIKCA verilir — {@code notifications} RLS ile workspace'e izole
 * edilmistir ama kullanicilar-arasi izolasyon RLS'in kapsami DISINDADIR (bkz. migration javadoc'u);
 * bu filtreyi unutmak, ayni workspace'teki bir uyenin baskasinin bildirimini gormesi/okundu
 * isaretlemesi anlamina gelirdi.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  @Query(
      "SELECT n FROM Notification n WHERE n.userId = :userId "
          + "AND (:unreadOnly = false OR n.readAt IS NULL) "
          + "ORDER BY n.createdAt DESC, n.id DESC")
  List<Notification> findFirstPage(
      @Param("userId") UUID userId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

  @Query(
      "SELECT n FROM Notification n WHERE n.userId = :userId "
          + "AND (:unreadOnly = false OR n.readAt IS NULL) "
          + "AND (n.createdAt < :cursorCreatedAt "
          + "OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId)) "
          + "ORDER BY n.createdAt DESC, n.id DESC")
  List<Notification> findNextPage(
      @Param("userId") UUID userId,
      @Param("unreadOnly") boolean unreadOnly,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  long countByUserIdAndReadAtIsNull(UUID userId);

  /**
   * Sahiplik kontrolu iceren tekil okuma: baska kullanicinin id'si icin bos doner (404 esdegeri).
   */
  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

  @Modifying
  @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
  int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * {@code payload} entity'ye MAPLENMEDI (bkz. Notification javadoc'u); satir once {@code save()}
   * ile olusturulur, bu metot ayni transaction icinde payload'i AYRICA yazar. {@code CAST(:payload
   * AS jsonb)} bilerek {@code ::jsonb} DEGIL: Hibernate 7'nin ordinal (?N) parametre + bitisik cast
   * parser tuzagi (Backend-Notlar 2026-09-18) yalniz {@code ?N::tip} sozdizimini etkiliyor; CAST()
   * fonksiyonu bu riski tamamen ortadan kaldirir (TaskCustomFieldRepository ile ayni tercih).
   */
  @Modifying
  @Query(
      value = "UPDATE notifications SET payload = CAST(:payload AS jsonb) WHERE id = :id",
      nativeQuery = true)
  void writePayload(@Param("id") UUID id, @Param("payload") String payloadJson);
}
