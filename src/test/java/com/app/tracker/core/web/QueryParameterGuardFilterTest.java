package com.app.tracker.core.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class QueryParameterGuardFilterTest {

  private final QueryParameterGuardFilter filter = new QueryParameterGuardFilter();

  private MockHttpServletRequest request(String query) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/search");
    r.setRequestURI("/api/v1/search");
    r.setQueryString(query);
    return r;
  }

  private MockHttpServletResponse run(
      MockHttpServletRequest req, AtomicReference<HttpServletRequest> seen) throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, (rq, rs) -> seen.set((HttpServletRequest) rq));
    return res;
  }

  @Test
  void nulInValueRejected() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    MockHttpServletResponse res = run(request("q=a%00b"), seen);
    assertEquals(400, res.getStatus());
    assertTrue(res.getContentType().startsWith("application/problem+json"));
    assertTrue(res.getContentAsString().contains("Sorgu parametresi gecersiz karakter iceriyor."));
    assertNull(seen.get());
  }

  @Test
  void nulInNameRejected() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    assertEquals(400, run(request("a%00b=1"), seen).getStatus());
    assertNull(seen.get());
  }

  @Test
  void nulWithEmptyNameStillRejected() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    assertEquals(400, run(request("=%00"), seen).getStatus());
    assertNull(seen.get());
  }

  @Test
  void emptyNameDroppedConsistently() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    assertEquals(200, run(request("q=sec&=&x=1&x=2&=y"), seen).getStatus());
    HttpServletRequest r = seen.get();
    assertEquals("sec", r.getParameter("q"));
    assertArrayEquals(new String[] {"1", "2"}, r.getParameterValues("x"));
    assertEquals(2, r.getParameterMap().size());
    assertNull(r.getParameter(""));
    assertEquals("q=sec&=&x=1&x=2&=y", r.getQueryString());
  }

  @Test
  void normalRequestPassesUnwrapped() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    MockHttpServletRequest req = request("q=%25%27");
    assertEquals(200, run(req, seen).getStatus());
    assertTrue(seen.get() == req);
  }

  @Test
  void emptyNameWithFormOrMultipartBodyRejected() throws Exception {
    for (String ct :
        new String[] {
          "application/x-www-form-urlencoded",
          "Application/X-WWW-Form-Urlencoded; charset=UTF-8",
          "multipart/form-data; boundary=xyz",
          "MULTIPART/mixed"
        }) {
      AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
      MockHttpServletRequest req = request("q=sec&=");
      req.setMethod("POST");
      req.setContentType(ct);
      MockHttpServletResponse res = run(req, seen);
      assertEquals(400, res.getStatus(), ct);
      assertTrue(res.getContentAsString().contains("Sorgu parametresi gecersiz"));
      assertNull(seen.get(), ct);
    }
  }

  @Test
  void emptyNameWithJsonBodyStillDropped() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    MockHttpServletRequest req = request("=x");
    req.setMethod("POST");
    req.setContentType("application/json");
    assertEquals(200, run(req, seen).getStatus());
  }

  @Test
  void formBodyWithoutEmptyNameUntouched() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    MockHttpServletRequest req = request("q=sec");
    req.setMethod("POST");
    req.setContentType("application/x-www-form-urlencoded");
    assertEquals(200, run(req, seen).getStatus());
    assertTrue(seen.get() == req);
  }

  @Test
  void nonApiPathSkipped() throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    MockHttpServletRequest req = request("q=%00");
    req.setRequestURI("/actuator/health");
    assertEquals(200, run(req, seen).getStatus());
  }
}
