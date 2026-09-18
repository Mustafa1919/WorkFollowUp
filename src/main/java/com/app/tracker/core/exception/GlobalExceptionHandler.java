package com.app.tracker.core.exception;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 2 — tum hatalar RFC 7807 (ProblemDetail) formatinda
 * disari verilir. Stack trace ASLA response body'sine konmaz; beklenmeyen hatalar sadece ic loglara
 * yazilir (Bolum "Detayli Hata Mesajlari vs. Guvenlik" trade-off'u).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessRuleException.class)
  public ProblemDetail handleBusinessRuleException(BusinessRuleException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setType(URI.create("https://api.app.com/errors/business-rule-violation"));
    problem.setTitle("Is Kurali Ihlali");
    return problem;
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setType(URI.create("https://api.app.com/errors/not-found"));
    problem.setTitle("Kaynak Bulunamadi");
    return problem;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Bu islemi yapmak icin yetkiniz yok.");
    problem.setType(URI.create("https://api.app.com/errors/forbidden"));
    problem.setTitle("Erisim Reddedildi");
    return problem;
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> invalidParams =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe ->
                    Map.of(
                        "field", fe.getField(), "reason", String.valueOf(fe.getDefaultMessage())))
            .toList();

    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Gonderilen verilerde %d adet hata bulundu.".formatted(invalidParams.size()));
    problem.setType(URI.create("https://api.app.com/errors/validation-failed"));
    problem.setTitle("Dogrulama Hatasi");
    problem.setProperty("invalid_params", invalidParams);
    return ResponseEntity.status(problem.getStatus()).body(problem);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleAllUncaughtException(Exception ex) {
    log.error("Beklenmeyen hata", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Sistemde beklenmeyen bir hata olustu. Lutfen daha sonra tekrar deneyin.");
    problem.setType(URI.create("https://api.app.com/errors/internal-error"));
    problem.setTitle("Sunucu Hatasi");
    return problem;
  }
}
