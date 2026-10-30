package com.app.tracker.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class RequestBodyLimitFilterTest {

  private static RequestBodyLimitFilter filter(long bytes) {
    RequestBodyLimitProperties props = new RequestBodyLimitProperties();
    props.setMaxRequestBodySize(DataSize.ofBytes(bytes));
    return new RequestBodyLimitFilter(props);
  }

  private static MockHttpServletRequest post(byte[] body) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/x");
    req.setContent(body);
    req.setContentType("application/json");
    return req;
  }

  @Test
  void contentLengthOverLimit_writes413WithoutCallingChain() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    filter(10).doFilter(post(new byte[11]), res, (rq, rs) -> called.set(true));
    assertEquals(413, res.getStatus());
    assertTrue(res.getContentType().startsWith("application/problem+json"));
    assertTrue(res.getContentAsString().contains("payload-too-large"));
    assertTrue(res.getContentAsString().contains("10 bayt"));
    assertFalse(called.get());
  }

  @Test
  void bodyAtLimit_passesAndReadsFully() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean read = new AtomicBoolean();
    filter(10)
        .doFilter(
            post(new byte[10]),
            res,
            (rq, rs) -> {
              read.set(((HttpServletRequest) rq).getInputStream().readAllBytes().length == 10);
            });
    assertTrue(read.get());
    assertEquals(200, res.getStatus());
  }

  @Test
  void undeclaredLengthOverLimit_throwsWhileReading() throws Exception {
    // Content-Length bilinmiyor (-1) gibi davranmak icin sarmalanmis istek.
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/api/v1/x") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    req.setContent(new byte[11]);
    req.setContentType("application/json");
    assertThrows(
        RequestBodyTooLargeException.class,
        () ->
            filter(10)
                .doFilter(
                    req,
                    new MockHttpServletResponse(),
                    (rq, rs) -> ((HttpServletRequest) rq).getInputStream().readAllBytes()));
  }

  @Test
  void multipartIsNotWrapped() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/x");
    req.setContentType("multipart/form-data; boundary=x");
    req.setContent(new byte[5]);
    MockFilterChain chain = new MockFilterChain();
    filter(10).doFilter(req, new MockHttpServletResponse(), chain);
    assertTrue(chain.getRequest() == req);
  }
}
