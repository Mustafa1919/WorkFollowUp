package com.app.tracker.core.exception;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.telemetry.TelemetryEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 *
 * <p>Dalga 4 -- yalniz {@link #handleAllUncaughtException} telemetriye ({@code error_events})
 * yazar. Diger handler'lar (BusinessRuleException, ResourceNotFoundException, validation, 403)
 * BEKLENEN kontrol akisidir, gercek bir bug degildir -- telemetriye tasinirsa "hata" sinyali
 * gurultuye boğulur.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final TelemetryEventPublisher telemetryEventPublisher;

  public GlobalExceptionHandler(TelemetryEventPublisher telemetryEventPublisher) {
    this.telemetryEventPublisher = telemetryEventPublisher;
  }

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
  public ProblemDetail handleAllUncaughtException(Exception ex, HttpServletRequest request) {
    log.error("Beklenmeyen hata", ex);
    reportToTelemetry(ex, request);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Sistemde beklenmeyen bir hata olustu. Lutfen daha sonra tekrar deneyin.");
    problem.setType(URI.create("https://api.app.com/errors/internal-error"));
    problem.setTitle("Sunucu Hatasi");
    return problem;
  }

  /**
   * Telemetri yayini asla bu handler'i patlatmamali -- zaten bir hata isleniyor, ikinci bir istisna
   * response'u bozar. userId/workspaceId eksikse (kimliksiz istek) null gecilir, publisher
   * null-safe'tir.
   */
  private void reportToTelemetry(Exception ex, HttpServletRequest request) {
    try {
      UUID workspaceId = TenantContext.getWorkspaceId();
      UUID userId = currentUserIdOrNull();
      telemetryEventPublisher.publishError(
          workspaceId,
          userId,
          "backend",
          ex.getClass().getSimpleName(),
          ex.getMessage(),
          request.getRequestURI(),
          Instant.now());
    } catch (RuntimeException telemetryFailure) {
      log.warn("Hata telemetrisi gonderilemedi", telemetryFailure);
    }
  }

  private static UUID currentUserIdOrNull() {
    try {
      return CurrentUser.id();
    } catch (AccessDeniedException e) {
      return null;
    }
  }
}
