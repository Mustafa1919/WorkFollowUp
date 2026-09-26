package com.app.tracker.accesstoken.controller;

import com.app.tracker.accesstoken.dto.AccessTokenCreatedResponse;
import com.app.tracker.accesstoken.dto.AccessTokenResponse;
import com.app.tracker.accesstoken.dto.CreateAccessTokenRequest;
import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import com.app.tracker.core.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0018 Karar 3 -- kullaniciya (workspace'e degil) baglidir, X-Workspace-Id gerektirmez.
 * Sinif-duzeyinde {@code @PreAuthorize} ile TUM endpoint'ler PAT-authenticated istekler icin
 * KAPALI: calinmis bir PAT kendi kendini yeni token uretip/iptal ederek yasam suresini uzatamaz --
 * token yonetimi yalniz gercek bir JWT oturumundan yapilabilir.
 */
@RestController
@RequestMapping("/api/v1/access-tokens")
@PreAuthorize("!hasAuthority('AUTH_PAT')")
public class AccessTokenController {

  private final PersonalAccessTokenService service;

  public AccessTokenController(PersonalAccessTokenService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<AccessTokenCreatedResponse> create(
      @Valid @RequestBody CreateAccessTokenRequest request) {
    Duration ttl =
        request.expiresInDays() == null ? null : Duration.ofDays(request.expiresInDays());
    var issued = service.create(CurrentUser.id(), request.name(), ttl);
    return ResponseEntity.status(HttpStatus.CREATED).body(AccessTokenCreatedResponse.from(issued));
  }

  @GetMapping
  public List<AccessTokenResponse> list() {
    return service.list(CurrentUser.id()).stream().map(AccessTokenResponse::from).toList();
  }

  @DeleteMapping("/{tokenId}")
  public ResponseEntity<Void> revoke(@PathVariable UUID tokenId) {
    service.revoke(CurrentUser.id(), tokenId);
    return ResponseEntity.noContent().build();
  }
}
