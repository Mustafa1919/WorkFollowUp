package com.app.tracker.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Beklentiler Dilim 04 R6/K6/R7/K7 orneklerinden gelir. DNS veya sunucu gerekmez. */
class ClientIpContractTest {

  static Stream<Arguments> chains() {
    return Stream.of(
        Arguments.of("203.0.113.9", List.of("1.1.1.1"), "203.0.113.9"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7"), "198.51.100.7"),
        Arguments.of("172.18.0.3", List.of("6.6.6.6, 198.51.100.7, 172.18.0.1"), "198.51.100.7"),
        Arguments.of("127.0.0.1", List.of(), "127.0.0.1"),
        Arguments.of("172.18.0.3", List.of("10.0.0.1, 192.168.0.1"), "10.0.0.1"),
        Arguments.of("127.0.0.1", List.of(""), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("   "), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("unknown"), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("<script>"), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7, unknown, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7, <script>, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7, , 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7,"), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("example.com, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("999.1.1.1, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7:80, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("::1", List.of("2001:db8::1"), "2001:db8::1"),
        Arguments.of("fc00::2", List.of("2001:0DB8:0:0:0:0:0:1, fd00::1"), "2001:db8::1"),
        Arguments.of("2001:db8::9", List.of("1.1.1.1"), "2001:db8::9"),
        Arguments.of("::ffff:10.0.0.1", List.of("198.51.100.7"), "198.51.100.7"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7, ::ffff:10.0.0.1"), "198.51.100.7"),
        Arguments.of("::ffff:203.0.113.9", List.of("1.1.1.1"), "203.0.113.9"),
        Arguments.of("127.0.0.1", List.of("::ffff:198.51.100.7"), "198.51.100.7"),
        Arguments.of("127.0.0.1", List.of("[2001:db8::1], 10.0.0.2"), "10.0.0.2"),
        Arguments.of("::1", List.of("fe80::1%eth0, fd00::1"), "fd00::1"),
        Arguments.of("172.18.0.3", List.of("6.6.6.6", "198.51.100.7, 172.18.0.1"), "198.51.100.7"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7", "unknown, 10.0.0.2"), "10.0.0.2"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7", ""), "127.0.0.1"),
        Arguments.of("127.0.0.1", List.of("198.51.100.7, unknown", "10.0.0.2"), "10.0.0.2"));
  }

  @ParameterizedTest(name = "R6_K6_R7_K7 [{index}] remote={0}, XFF={1}")
  @MethodSource("chains")
  void R6_K6_R7_K7_contractExamplesAndLiteralBoundaries(
      String remote, List<String> headers, String expected) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remote);
    headers.forEach(header -> request.addHeader("X-Forwarded-For", header));
    assertEquals(expected, new ClientIpResolver(new ClientIpProperties()).resolve(request));
    assertEquals(remote, request.getRemoteAddr(), "resolver remoteAddr'i degistirmemeli");
  }
}
