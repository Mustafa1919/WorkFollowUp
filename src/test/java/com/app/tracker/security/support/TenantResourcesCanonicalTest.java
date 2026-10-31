package com.app.tracker.security.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** {@link TenantResources#canonical} birim testi: sira bagimsiz, alan duyarli karsilastirma. */
class TenantResourcesCanonicalTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  private String canon(String json) {
    return TenantResources.canonical(mapper.readTree(json));
  }

  @Test
  void arrayOrderAndKeyOrderDoNotMatter() {
    assertEquals(
        canon("[{\"id\":\"1\",\"name\":\"a\"},{\"id\":\"2\",\"name\":\"b\"}]"),
        canon("[{\"name\":\"b\",\"id\":\"2\"},{\"id\":\"1\",\"name\":\"a\"}]"));
  }

  @Test
  void fieldChangeAddedOrRemovedItemIsDetected() {
    String base = canon("{\"data\":[{\"id\":\"1\",\"status\":\"To Do\"}]}");
    assertNotEquals(base, canon("{\"data\":[{\"id\":\"1\",\"status\":\"Done\"}]}"));
    assertNotEquals(base, canon("{\"data\":[]}"));
    assertNotEquals(
        base,
        canon("{\"data\":[{\"id\":\"1\",\"status\":\"To Do\"},{\"id\":\"2\",\"status\":\"x\"}]}"));
  }

  @Test
  void scalarTypesStayDistinct() {
    assertNotEquals(canon("{\"v\":1}"), canon("{\"v\":\"1\"}"));
    assertNotEquals(canon("{\"v\":null}"), canon("{\"v\":\"null\"}"));
  }
}
