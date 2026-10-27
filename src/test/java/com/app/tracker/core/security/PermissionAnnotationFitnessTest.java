package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

class PermissionAnnotationFitnessTest {

  private static final Pattern PERMISSION_CALL =
      Pattern.compile("\\b(can|canAny)\\s*\\(([^)]*)\\)");
  private static final Pattern PERMISSION_NAME = Pattern.compile("'([^']*)'");

  @Test
  void controllerAuthorizationUsesKnownPermissionsInsteadOfRoles() throws ClassNotFoundException {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<AuthorizationExpression> expressions = new ArrayList<>();
    for (var candidate : scanner.findCandidateComponents("com.app.tracker")) {
      Class<?> controller =
          Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
      collect(
          expressions,
          controller.getName(),
          AnnotationUtils.findAnnotation(controller, PreAuthorize.class));
      for (Method method : controller.getDeclaredMethods()) {
        collect(
            expressions,
            controller.getName() + "#" + method.getName(),
            AnnotationUtils.findAnnotation(method, PreAuthorize.class));
      }
    }

    boolean foundCan = false;
    for (AuthorizationExpression expression : expressions) {
      String location = expression.location();
      String value = expression.value();
      assertFalse(
          value.contains("hasCurrentWorkspaceRole"),
          location + ": hasCurrentWorkspaceRole kullanilamaz: " + value);
      assertFalse(
          value.contains("hasWorkspaceRole"),
          location + ": hasWorkspaceRole kullanilamaz: " + value);
      Matcher calls = PERMISSION_CALL.matcher(value);
      while (calls.find()) {
        foundCan |= calls.group(1).equals("can");
        Matcher names = PERMISSION_NAME.matcher(calls.group(2));
        while (names.find()) {
          String name = names.group(1);
          assertDoesNotThrow(
              () -> WorkspacePermission.valueOf(name),
              location + ": bilinmeyen izin '" + name + "': " + value);
        }
      }
    }
    assertTrue(foundCan, "Controller taramasinda en az bir can(...) ifadesi bulunmali.");
  }

  private static void collect(
      List<AuthorizationExpression> expressions, String location, PreAuthorize annotation) {
    if (annotation != null) {
      expressions.add(new AuthorizationExpression(location, annotation.value()));
    }
  }

  private record AuthorizationExpression(String location, String value) {}
}
