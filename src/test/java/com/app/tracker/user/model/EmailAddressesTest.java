package com.app.tracker.user.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailAddressesTest {

  @Test
  void stripsAndLowercasesAsciiOnly() {
    assertEquals("foo.bar@example.com", EmailAddresses.canonical("  Foo.Bar@Example.COM "));
  }

  @Test
  void leavesNonAsciiLettersUntouched() {
    assertEquals("İst@x.tr", EmailAddresses.canonical("İst@X.tr"));
    assertEquals("ä@b.de", EmailAddresses.canonical("ä@B.de"));
    assertEquals("Ş@x.tr", EmailAddresses.canonical("Ş@x.tr"));
  }

  @Test
  void stripsUnicodeWhitespace() {
    assertEquals("a@x.test", EmailAddresses.canonical(" A@x.test "));
  }

  @Test
  void alreadyCanonicalIsUnchanged() {
    assertEquals("a-z_0.9@x.test", EmailAddresses.canonical("a-z_0.9@x.test"));
  }

  @Test
  void nullThrows() {
    assertThrows(NullPointerException.class, () -> EmailAddresses.canonical(null));
  }
}
