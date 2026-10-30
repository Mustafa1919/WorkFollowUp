package com.app.tracker.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Istek govdesini Jackson cozumlemeden ONCE bayt duzeyinde sinirlar (SEC-08).
 *
 * <p>Sira: {@code HIGHEST_PRECEDENCE + 10}. Spring Security FilterChainProxy -100'dedir; bu filtre
 * ondan once calisir ki kimliksiz uclara (login vb.) gelen devasa govde de 401 yerine 413 alsin.
 * Guvenlik zincirine EKLENMEZ.
 *
 * <p>Content-Length sinirdan buyukse govde okunmadan 413 yazilir. Chunked (veya yalan bildirimli)
 * istekte istek, okunan bayti sayan bir sarmalayiciyla gecirilir; sinir asilinca {@link
 * RequestBodyTooLargeException} atilir (GlobalExceptionHandler 413'e cevirir).
 *
 * <p>Hata yaniti {@code sendError} ile DEGIL dogrudan yazilir: sendError {@code /error} dispatch'i
 * baslatir ve yanit 401'e donerdi. {@code multipart/*} istekler sarilmaz (yukleme ucu yok).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

  private final long maxBytes;

  public RequestBodyLimitFilter(RequestBodyLimitProperties properties) {
    this.maxBytes = properties.getMaxRequestBodySize().toBytes();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (isMultipart(request.getContentType())) {
      filterChain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > maxBytes) {
      writeTooLarge(response, new RequestBodyTooLargeException(maxBytes));
      return;
    }
    filterChain.doFilter(new LimitedRequest(request, maxBytes), response);
  }

  /** Genel 413 problem govdesini dogrudan yazar (sendError yok); IdempotencyFilter da kullanir. */
  static void writeTooLarge(HttpServletResponse response, RequestBodyTooLargeException ex)
      throws IOException {
    response.setStatus(413);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"https://api.app.com/errors/payload-too-large\","
                + "\"title\":\"Istek Govdesi Cok Buyuk\",\"status\":413,\"detail\":\""
                + ex.detail()
                + "\"}");
  }

  private static boolean isMultipart(String contentType) {
    return contentType != null
        && contentType.toLowerCase(Locale.ROOT).trim().startsWith("multipart/");
  }

  /** Okunan bayti sayan istek; sinir asilinca {@link RequestBodyTooLargeException} atar. */
  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private final long limit;
    private ServletInputStream stream;

    LimitedRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new CountingStream(super.getInputStream(), limit);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class CountingStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long total;

    CountingStream(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b != -1) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = delegate.read(buf, off, len);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws IOException {
      total += n;
      if (total > limit) {
        throw new RequestBodyTooLargeException(limit);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
