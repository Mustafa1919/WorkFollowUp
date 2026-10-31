package com.app.tracker.meeting.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Null/boş değer geçerlidir; aksi halde değer host'lu, mutlak bir {@code https} URL olmalıdır. */
@Documented
@Constraint(validatedBy = HttpsUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpsUrl {

  String message() default "must be an absolute https URL";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
