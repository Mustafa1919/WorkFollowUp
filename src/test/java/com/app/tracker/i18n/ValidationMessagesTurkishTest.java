package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.tracker.core.security.dto.RegisterRequest;
import com.app.tracker.project.dto.CreateProjectRequest;
import com.app.tracker.task.dto.CreateTaskRequest;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationMessagesTurkishTest {
  private Locale original;

  @BeforeEach
  void saveLocale() {
    original = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(original);
  }

  private ValidatorFactory factory(String language) {
    var config = Validation.byDefaultProvider().configure();
    MessageInterpolator delegate = config.getDefaultMessageInterpolator();
    Locale requested = Locale.forLanguageTag(language);
    return config
        .messageInterpolator(
            new MessageInterpolator() {
              @Override
              public String interpolate(String template, Context context) {
                return delegate.interpolate(template, context, requested);
              }

              @Override
              public String interpolate(String template, Context context, Locale locale) {
                return delegate.interpolate(template, context, locale);
              }
            })
        .buildValidatorFactory();
  }

  private RegisterRequest register(String email, boolean consent) {
    return new RegisterRequest(email, "GeçerliParola123!", "Ayşe Öztürk", consent, "v1");
  }

  private CreateTaskRequest task(int points, int tags) {
    return new CreateTaskRequest(
        "Görev",
        null,
        null,
        null,
        points,
        null,
        Collections.nCopies(tags, UUID.fromString("00000000-0000-0000-0000-000000000001")));
  }

  private void message(Validator validator, Object dto, String field, String expected) {
    assertThat(validator.validateProperty(dto, field))
        .singleElement()
        .satisfies(v -> assertThat(v.getMessage()).isEqualTo(expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_notBlankIsTurkishInEnglishLocale(String locale) {
    try (var f = factory(locale)) {
      message(f.getValidator(), register("", true), "email", "Bu alan boş bırakılamaz.");
      message(
          f.getValidator(),
          new CreateProjectRequest("", "Proje"),
          "key",
          "Bu alan boş bırakılamaz.");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_sizeForTextIsTurkish(String locale) {
    try (var f = factory(locale)) {
      // Size serves both lists and text: a neutral Turkish sentence is also valid.
      assertThat(
              f.getValidator()
                  .validateProperty(new CreateProjectRequest("K".repeat(11), "Proje"), "key"))
          .singleElement()
          .satisfies(
              v ->
                  assertThat(v.getMessage())
                      .contains("0", "10")
                      .doesNotContain("{min}", "{max}")
                      .matches("[A-ZÇĞİÖŞÜ].*[çğıİöşü].*\\."));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_emailIsTurkish(String locale) {
    try (var f = factory(locale)) {
      message(
          f.getValidator(),
          register("geçersiz", true),
          "email",
          "Geçerli bir e-posta adresi girin.");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_minIsTurkish(String locale) {
    try (var f = factory(locale)) {
      message(f.getValidator(), task(-1, 0), "storyPoint", "Değer en az 0 olmalı.");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_assertTrueIsTurkish(String locale) {
    try (var f = factory(locale)) {
      message(
          f.getValidator(),
          register("ayse@example.com", false),
          "privacyNoticeAccepted",
          "Bu alanın onaylanması gerekiyor.");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_validDtosAndBoundaryValuesAreAccepted(String locale) {
    try (var f = factory(locale)) {
      Validator validator = f.getValidator();
      assertThat(validator.validate(register("ayse@example.com", true))).isEmpty();
      assertThat(validator.validate(new CreateProjectRequest("K".repeat(10), "P".repeat(100))))
          .isEmpty();
      assertThat(validator.validate(task(0, 20))).isEmpty();
      assertThat(validator.validate(task(100, 0))).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_listSizeViolationDoesNotMentionCharacterCount(String locale) {
    try (var f = factory(locale)) {
      assertThat(f.getValidator().validateProperty(task(0, 21), "tagIds"))
          .singleElement()
          .satisfies(
              v ->
                  assertThat(v.getMessage())
                      .contains("20")
                      .doesNotContain("karakter")
                      .endsWith("."));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K2_defaultProviderUsesRootBundleWithoutSpring(String ignored) {
    try (var f = Validation.byDefaultProvider().configure().buildValidatorFactory()) {
      message(
          f.getValidator(),
          new CreateProjectRequest("", "Proje"),
          "key",
          "Bu alan boş bırakılamaz.");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US"})
  void K6_textAndListsShareNeutralMessageAndPasswordOverrideIsPreserved(String locale) {
    try (var f = factory(locale)) {
      message(
          f.getValidator(),
          new CreateProjectRequest("K".repeat(11), "Proje"),
          "key",
          "Boyut 0 ile 10 arasında olmalı.");
      message(f.getValidator(), task(0, 21), "tagIds", "Boyut 0 ile 20 arasında olmalı.");
      message(
          f.getValidator(),
          new RegisterRequest("ayse@example.com", "kısa", "Ayşe", true, "v1"),
          "password",
          "Parola en az 12 karakter olmalıdır.");
      assertThat(f.getValidator().validate(new CreateProjectRequest("K".repeat(10), "Proje")))
          .isEmpty();
      assertThat(f.getValidator().validate(task(0, 20))).isEmpty();
    }
  }
}
