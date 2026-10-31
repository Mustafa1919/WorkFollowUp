package com.app.tracker.core.audit;

/** SEC-23 -- guvenlik acisindan anlamli olaylar; {@link SecurityAuditLog} ile yazilir. */
public enum SecurityAuditEvent {
  LOGIN_FAILED,
  ACCOUNT_LOCKED,
  REFRESH_TOKEN_REUSE,
  PASSWORD_RESET_COMPLETED,
  ACCESS_DENIED,
  PAT_CREATED,
  PAT_REVOKED,
  MEMBER_ADDED,
  MEMBER_ROLE_CHANGED,
  MEMBER_REMOVED,
  WS_SESSION_TERMINATED
}
