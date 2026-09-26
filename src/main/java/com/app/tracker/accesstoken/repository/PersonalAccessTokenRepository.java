package com.app.tracker.accesstoken.repository;

import com.app.tracker.accesstoken.model.PersonalAccessToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

  Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

  List<PersonalAccessToken> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
