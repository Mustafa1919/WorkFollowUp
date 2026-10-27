package com.app.tracker.user.dto;

import com.app.tracker.user.model.User;
import java.util.UUID;

/** {@code fullName} eski kayitlarda null olabilir. */
public record MeResponse(UUID id, String email, String fullName) {

  public static MeResponse from(User user) {
    return new MeResponse(user.getId(), user.getEmail(), user.getFullName());
  }
}
