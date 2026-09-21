package com.app.tracker.notification.repository;

import com.app.tracker.notification.model.SlackIntegration;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Cagiran {@code @Transactional} servis katmanindadir (TenancyGuardAspect, repository proxy'sinin
 * DISINDA kosar: arayuz metoduna {@code @Transactional} konmaz). Tenant izolasyonu RLS ile
 * saglanir: baska workspace'in satiri {@code findById}'de HIC DONMEZ.
 */
public interface SlackIntegrationRepository extends JpaRepository<SlackIntegration, UUID> {}
