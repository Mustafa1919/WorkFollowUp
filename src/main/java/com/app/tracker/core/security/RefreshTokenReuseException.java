package com.app.tracker.core.security;

/**
 * Grace suresi disinda refresh token tekrar kullanimi. BusinessRuleException DEGILDIR: istemciye
 * 401 doner ve aile iptali ayri transaction'da (RefreshTokenFamilyRevoker) commit edilir.
 */
public class RefreshTokenReuseException extends RuntimeException {

  public RefreshTokenReuseException(String message) {
    super(message);
  }
}
