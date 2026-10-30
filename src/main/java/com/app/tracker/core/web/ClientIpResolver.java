package com.app.tracker.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Istegin istemci IP'sini yalniz guvenilen proxy'lerin {@code X-Forwarded-For} bilgisinden cozer
 * (SEC-17). Uygulamada ham {@code X-Forwarded-For} okuyan TEK yer burasidir.
 *
 * <p>Kural: {@code remoteAddr} guvenilir listede degilse XFF yok sayilir (istemci uydurabilir).
 * Guvenilirse XFF sagdan sola yurunur; guvenilir adresler atlanir, ilk guvenilmeyen adres
 * istemcidir. IP literali olmayan bir parca (ornek {@code unknown}, bos) gorulurse yuruyus durur ve
 * o parcanin sagindaki son guvenilir adres doner. Butun zincir guvenilirse en soldaki adres doner.
 *
 * <p>Tomcat {@code RemoteIpValve} / {@code server.forward-headers-strategy} BILEREK kullanilmaz:
 * valve X-Forwarded-Host/Proto'yu da isler ve {@code request.getRemoteAddr()}'i degistirir.
 *
 * <p>Ad cozumlemesi (DNS) YAPILMAZ: yalniz IP literalleri kabul edilir. Donus degeri normalize
 * edilmis metindir (IPv4 noktali, IPv6 RFC 5952 kisa bicim; IPv4-mapped IPv6 IPv4'e indirgenir).
 */
@Component
public class ClientIpResolver {

  private static final String XFF = "X-Forwarded-For";
  private static final Pattern IPV4 =
      Pattern.compile(
          "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
  private static final int MAX_LITERAL_LENGTH = 45;

  private final List<Cidr> trusted;

  public ClientIpResolver(ClientIpProperties properties) {
    List<Cidr> parsed = new ArrayList<>();
    for (String cidr : properties.getTrustedProxies()) {
      parsed.add(Cidr.parse(cidr));
    }
    this.trusted = List.copyOf(parsed);
  }

  /** Cozulmus istemci IP'si (normalize edilmis metin). */
  public String resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    InetAddress remote = parseLiteral(remoteAddr);
    if (remote == null) {
      // Servlet kapsayicisi her zaman literal verir; olmazsa ham deger (XFF'e guvenilmez).
      return remoteAddr;
    }
    if (!isTrusted(remote)) {
      return format(remote);
    }
    List<String> parts = forwardedParts(request);
    if (parts.isEmpty()) {
      return format(remote);
    }
    InetAddress lastTrusted = remote;
    for (int i = parts.size() - 1; i >= 0; i--) {
      InetAddress hop = parseLiteral(parts.get(i));
      if (hop == null) {
        return format(lastTrusted);
      }
      if (!isTrusted(hop)) {
        return format(hop);
      }
      lastTrusted = hop;
    }
    return format(lastTrusted);
  }

  boolean isTrusted(InetAddress address) {
    for (Cidr cidr : trusted) {
      if (cidr.contains(address)) {
        return true;
      }
    }
    return false;
  }

  /** Birden fazla XFF basligi gelirse sirayla birlestirilir (RFC 7230 3.2.2). */
  private static List<String> forwardedParts(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaders(XFF);
    if (headers == null) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    for (String header : Collections.list(headers)) {
      if (header == null) {
        continue;
      }
      for (String part : header.split(",", -1)) {
        parts.add(part.trim());
      }
    }
    if (parts.size() == 1 && parts.get(0).isEmpty()) {
      return List.of();
    }
    return parts;
  }

  /** Yalniz IP literali; ad cozumlemesi yapmaz. Literal degilse {@code null}. */
  static InetAddress parseLiteral(String value) {
    if (value == null) {
      return null;
    }
    String s = value.trim();
    if (s.isEmpty() || s.length() > MAX_LITERAL_LENGTH) {
      return null;
    }
    if (s.indexOf(':') < 0) {
      if (!IPV4.matcher(s).matches()) {
        return null;
      }
      return toAddress(s);
    }
    // IPv6: yalniz hex, ':' ve (gomulu IPv4 icin) '.'; zone id ('%') ve koseli parantez yok.
    // Ilk karakter hex ya da ':' oldugu icin JDK literal yolunu izler, DNS'e gitmez.
    char first = s.charAt(0);
    if (first != ':' && Character.digit(first, 16) < 0) {
      return null;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != ':' && c != '.' && Character.digit(c, 16) < 0) {
        return null;
      }
    }
    return toAddress(s);
  }

  private static InetAddress toAddress(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  /** IPv4 noktali; IPv6 RFC 5952 kisa bicim (en uzun sifir dizisi '::'). */
  static String format(InetAddress address) {
    if (address instanceof Inet4Address) {
      return address.getHostAddress();
    }
    byte[] b = address.getAddress();
    int[] groups = new int[8];
    for (int i = 0; i < 8; i++) {
      groups[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
    }
    int bestStart = -1;
    int bestLen = 0;
    for (int i = 0; i < 8; ) {
      if (groups[i] != 0) {
        i++;
        continue;
      }
      int j = i;
      while (j < 8 && groups[j] == 0) {
        j++;
      }
      if (j - i > bestLen) {
        bestStart = i;
        bestLen = j - i;
      }
      i = j;
    }
    if (bestLen < 2) {
      bestStart = -1;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == bestStart) {
        sb.append("::");
        i += bestLen - 1;
        continue;
      }
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
        sb.append(':');
      }
      sb.append(Integer.toHexString(groups[i]));
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /** Ag/onek; IPv4 ve IPv6 ayri ailelerdir (IPv4-mapped IPv6 zaten IPv4'e indirgenir). */
  private static final class Cidr {

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) {
      this.network = network;
      this.prefix = prefix;
    }

    static Cidr parse(String text) {
      String s = text.trim();
      int slash = s.indexOf('/');
      String addressPart = slash < 0 ? s : s.substring(0, slash);
      InetAddress address = parseLiteral(addressPart);
      if (address == null) {
        throw new IllegalArgumentException("Gecersiz guvenilir proxy CIDR: " + text);
      }
      int max = address.getAddress().length * 8;
      int prefix;
      try {
        prefix = slash < 0 ? max : Integer.parseInt(s.substring(slash + 1).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Gecersiz guvenilir proxy CIDR: " + text, e);
      }
      if (prefix < 0 || prefix > max) {
        throw new IllegalArgumentException("Gecersiz guvenilir proxy CIDR: " + text);
      }
      return new Cidr(address.getAddress(), prefix);
    }

    boolean contains(InetAddress address) {
      byte[] candidate = address.getAddress();
      if (candidate.length != network.length) {
        return false;
      }
      int fullBytes = prefix / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidate[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xff << (8 - rest)) & 0xff;
      return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }
}
