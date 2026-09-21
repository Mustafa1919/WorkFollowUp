package com.app.tracker.integration.repository;

import com.app.tracker.integration.model.WebhookIntegration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Yonetim endpoint'leri icin; tenant izolasyonu RLS ile saglanir (V13). */
public interface WebhookIntegrationRepository extends JpaRepository<WebhookIntegration, UUID> {

  List<WebhookIntegration> findAllByOrderByCreatedAtAsc();
}
