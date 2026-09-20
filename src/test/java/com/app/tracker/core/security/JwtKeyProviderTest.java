package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * Dev JWT anahtarlari PUBLIC repoda durur (bkz. JwtKeyProvider javadoc). staging/prod'da bu
 * anahtarlarla acilis reddedilmezse, ortam degiskeni unutuldugunda herkesin bildigi anahtarla
 * imzalanmis (SYSTEM_ADMIN rolu dahil) sahte token kabul edilirdi. Docker/Spring context
 * gerektirmez.
 */
class JwtKeyProviderTest {

  private static final String DEV_PRIVATE = "classpath:keys/dev-jwt-private.pem";
  private static final String DEV_PUBLIC = "classpath:keys/dev-jwt-public.pem";

  private static JwtProperties properties(String privateKey, String publicKey) {
    JwtProperties properties = new JwtProperties();
    properties.setPrivateKeyLocation(privateKey);
    properties.setPublicKeyLocation(publicKey);
    return properties;
  }

  private static MockEnvironment profiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  private static JwtKeyProvider load(JwtProperties properties, MockEnvironment environment) {
    return new JwtKeyProvider(properties, new DefaultResourceLoader(), environment);
  }

  @ParameterizedTest
  @ValueSource(strings = {"staging", "prod"})
  void devKeysAreRejectedInProtectedProfiles(String profile) {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> load(properties(DEV_PRIVATE, DEV_PUBLIC), profiles(profile)));

    assertTrue(e.getMessage().contains("DEV JWT anahtarlari"));
  }

  @Test
  void aSingleDevKeyIsEnoughToRejectStartup(@TempDir Path dir) throws Exception {
    Path realPrivate = writeKeyPair(dir).privateKey();

    // Ozel anahtar gercek, genel anahtar dev: karisik yapilandirma da reddedilmeli.
    assertThrows(
        IllegalStateException.class,
        () -> load(properties(realPrivate.toUri().toString(), DEV_PUBLIC), profiles("prod")));
  }

  @Test
  void missingLocationIsRejectedInProtectedProfile() {
    assertThrows(IllegalStateException.class, () -> load(properties(null, null), profiles("prod")));
  }

  @Test
  void realKeyFilesAreAcceptedInProdAndSigningMaterialLoads(@TempDir Path dir) throws Exception {
    KeyFiles files = writeKeyPair(dir);

    JwtKeyProvider provider =
        load(
            properties(files.privateKey().toUri().toString(), files.publicKey().toUri().toString()),
            profiles("prod"));

    assertNotNull(provider.getPrivateKey());
    assertNotNull(provider.getPublicKey());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test", "migrate"})
  void devKeysStillWorkOutsideProtectedProfiles(String profile) {
    assertNotNull(load(properties(DEV_PRIVATE, DEV_PUBLIC), profiles(profile)).getPrivateKey());
  }

  @Test
  void devKeysStillWorkWithoutAnyActiveProfile() {
    // Yerel calistirma ve testler profil vermez.
    assertNotNull(load(properties(DEV_PRIVATE, DEV_PUBLIC), profiles()).getPrivateKey());
  }

  private record KeyFiles(Path privateKey, Path publicKey) {}

  private static KeyFiles writeKeyPair(Path dir) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    Path privateKey = dir.resolve("private.pem");
    Path publicKey = dir.resolve("public.pem");
    Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    return new KeyFiles(privateKey, publicKey);
  }

  private static String pem(String type, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }
}
