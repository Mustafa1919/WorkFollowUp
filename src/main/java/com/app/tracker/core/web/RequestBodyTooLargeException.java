package com.app.tracker.core.web;

import java.io.IOException;

/**
 * Istek govdesi izin verilen siniri astiginda govde okunurken atilir. IOException'dir: okuyan kod
 * (Jackson, ham okumalar) onu aktarir; GlobalExceptionHandler neden zincirinde bulup 413'e cevirir.
 */
public class RequestBodyTooLargeException extends IOException {

  private final long limitBytes;

  public RequestBodyTooLargeException(long limitBytes) {
    super("Istek govdesi siniri asildi");
    this.limitBytes = limitBytes;
  }

  public long getLimitBytes() {
    return limitBytes;
  }

  public String detail() {
    return "Istek govdesi en fazla " + limitBytes + " bayt olabilir.";
  }
}
