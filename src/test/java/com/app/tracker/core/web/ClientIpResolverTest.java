package com.app.tracker.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  private final ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties());

  private static MockHttpServletRequest req(String remote, String... xff) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    r.setRemoteAddr(remote);
    for (String h : xff) {
      r.addHeader("X-Forwarded-For", h);
    }
    return r;
  }

  @Test
  void untrustedRemote_ignoresXff() {
    assertEquals("203.0.113.9", resolver.resolve(req("203.0.113.9", "1.1.1.1")));
  }

  @Test
  void trustedRemote_withoutXff_returnsRemote() {
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1")));
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1", "")));
  }

  @Test
  void trustedRemote_singleClient() {
    assertEquals("198.51.100.7", resolver.resolve(req("127.0.0.1", "198.51.100.7")));
  }

  @Test
  void walksFromRight_skipsTrusted_ignoresSpoofedLeft() {
    assertEquals(
        "198.51.100.7", resolver.resolve(req("172.18.0.3", "6.6.6.6, 198.51.100.7, 172.18.0.1")));
  }

  @Test
  void multipleHeaderLines_areJoinedInOrder() {
    assertEquals(
        "198.51.100.7", resolver.resolve(req("172.18.0.3", "6.6.6.6", "198.51.100.7, 10.0.0.2")));
  }

  @Test
  void allTrusted_returnsLeftmost() {
    assertEquals("10.1.2.3", resolver.resolve(req("127.0.0.1", "10.1.2.3, 192.168.1.1")));
  }

  @Test
  void nonLiteral_stopsWalk_returnsLastTrusted() {
    assertEquals("10.0.0.5", resolver.resolve(req("127.0.0.1", "unknown, 10.0.0.5")));
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1", "<script>")));
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1", "1.2.3.4, ")));
    assertEquals("10.0.0.5", resolver.resolve(req("127.0.0.1", "1.2.3.4,,10.0.0.5")));
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1", "1.2.3.4:5555")));
    assertEquals("127.0.0.1", resolver.resolve(req("127.0.0.1", "example.com")));
  }

  @Test
  void ipv6_classifiedAndNormalized() {
    assertEquals("2001:db8::1", resolver.resolve(req("::1", "2001:db8::1")));
    assertEquals("2001:db8::1", resolver.resolve(req("0:0:0:0:0:0:0:1", "2001:0DB8:0:0:0:0:0:1")));
    assertEquals("::1", resolver.resolve(req("0:0:0:0:0:0:0:1")));
    assertEquals("2001:db8::1", resolver.resolve(req("2001:db8::1", "1.1.1.1")));
    assertEquals("198.51.100.7", resolver.resolve(req("fd00::2", "198.51.100.7")));
  }

  @Test
  void ipv4MappedIpv6_followsIpv4Rules() {
    assertEquals("198.51.100.7", resolver.resolve(req("::ffff:10.0.0.1", "198.51.100.7")));
    assertEquals("203.0.113.9", resolver.resolve(req("::ffff:203.0.113.9", "1.1.1.1")));
    assertEquals("198.51.100.7", resolver.resolve(req("127.0.0.1", "::ffff:198.51.100.7")));
  }

  @Test
  void parseLiteral_rejectsNonLiterals() {
    assertNull(ClientIpResolver.parseLiteral("localhost"));
    assertNull(ClientIpResolver.parseLiteral("1.2.3"));
    assertNull(ClientIpResolver.parseLiteral("256.1.1.1"));
    assertNull(ClientIpResolver.parseLiteral("01.2.3.4"));
    assertNull(ClientIpResolver.parseLiteral("fe80::1%eth0"));
    assertNull(ClientIpResolver.parseLiteral("[::1]"));
    assertNull(ClientIpResolver.parseLiteral(".:"));
    assertNull(ClientIpResolver.parseLiteral("1:2:3:4:5:6:7:8:9"));
  }

  @Test
  void format_rfc5952() {
    assertEquals(
        "1:0:2::", ClientIpResolver.format(ClientIpResolver.parseLiteral("1:0:2:0:0:0:0:0")));
    assertEquals("::", ClientIpResolver.format(ClientIpResolver.parseLiteral("::")));
    assertEquals(
        "1:0:1:0:1:0:1:0",
        ClientIpResolver.format(ClientIpResolver.parseLiteral("1:0:1:0:1:0:1:0")));
    assertEquals(
        "1::2:0:0:3", ClientIpResolver.format(ClientIpResolver.parseLiteral("1:0:0:0:2:0:0:3")));
  }

  @Test
  void customTrustedList_andInvalidCidrRejected() {
    ClientIpProperties props = new ClientIpProperties();
    props.setTrustedProxies(List.of("203.0.113.0/24"));
    ClientIpResolver custom = new ClientIpResolver(props);
    assertEquals("1.1.1.1", custom.resolve(req("203.0.113.9", "1.1.1.1")));
    assertEquals("127.0.0.1", custom.resolve(req("127.0.0.1", "1.1.1.1")));

    props.setTrustedProxies(List.of("10.0.0.0/33"));
    assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(props));
    props.setTrustedProxies(List.of("not-an-ip/8"));
    assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(props));
  }
}
