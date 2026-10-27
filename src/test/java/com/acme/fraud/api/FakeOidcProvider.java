package com.acme.fraud.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Minimal OpenID Connect provider for tests: serves the discovery document and the JWKS over HTTP
 * and signs RS256 tokens, so the application's issuer-based JWT decoder is exercised exactly as it
 * would be against a real IdP (discovery, key fetch by {@code kid}, signature, {@code exp}, {@code
 * iss}). Built on the JDK HTTP server and Nimbus, which are already on the test classpath.
 */
final class FakeOidcProvider implements AutoCloseable {

  static final String KEY_ID = "test-key-1";

  private final HttpServer server;
  private final RSAKey signingKey;
  private final String issuer;

  FakeOidcProvider() {
    try {
      signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    } catch (IOException | JOSEException e) {
      throw new IllegalStateException("Cannot start fake OIDC provider", e);
    }
    issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/fraud";
    String discovery =
        """
        {"issuer":"%1$s","jwks_uri":"%1$s/jwks","authorization_endpoint":"%1$s/auth",\
        "token_endpoint":"%1$s/token","response_types_supported":["code"],\
        "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"]}"""
            .formatted(issuer);
    String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
    server.createContext(
        "/realms/fraud/.well-known/openid-configuration", ex -> respond(ex, discovery));
    server.createContext("/realms/fraud/jwks", ex -> respond(ex, jwks));
    server.start();
  }

  String issuer() {
    return issuer;
  }

  /** Claims the IdP would put in a valid, short-lived access token. */
  JWTClaimsSet.Builder validClaims(String subject, String... roles) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject(subject)
        .claim("roles", List.of(roles))
        .expirationTime(new Date(System.currentTimeMillis() + 300_000));
  }

  /** Signs with the provider's published key. */
  String sign(JWTClaimsSet claims) {
    return sign(claims, signingKey);
  }

  /** Signs with the given key, e.g. an attacker's key that reuses the published {@code kid}. */
  static String sign(JWTClaimsSet claims, RSAKey key) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A key pair the application does not trust, with the same {@code kid} as the published key. */
  static RSAKey rogueKey() {
    try {
      return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void respond(HttpExchange ex, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
