package com.platform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** The production decoder from SecurityConfig against a real JWKS endpoint (no Spring context, no test decoder). */
class JwtDecoderValidationTest {

    private static final String ISSUER = "http://issuer.test/realms/sensor-platform";
    private static final String AUDIENCE = "sensor-platform-api";
    private static final String KID = "test-key";

    private static RSAKey signingKey;
    private static RSAKey otherKey;
    private static HttpServer jwks;
    private static JwtDecoder decoder;

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID(KID).generate();
        // same key id, different key material: only the signature check can reject it
        otherKey = new RSAKeyGenerator(2048).keyID(KID).generate();
        byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        decoder = new SecurityConfig()
                .jwtDecoder("http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs", ISSUER, AUDIENCE);
    }

    @AfterAll
    static void stopJwksServer() {
        jwks.stop(0);
    }

    private static String signed(RSAKey key, String issuer, String audience, long expiresInSeconds) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(),
                claims(issuer, audience, expiresInSeconds));
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(String issuer, String audience, long expiresInSeconds) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user")
                .audience(List.of(audience))
                .issueTime(new Date(System.currentTimeMillis() - 60_000))
                .expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000))
                .build();
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(signed(signingKey, ISSUER, AUDIENCE, 300));

        assertThat(jwt.getSubject()).isEqualTo("user");
        assertThat(jwt.getAudience()).contains(AUDIENCE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "wrong issuer, issuer",
        "wrong audience, audience",
        "expired, expired",
        "signed by another key, signature",
        "unsigned alg none, unsigned"
    })
    void invalidTokensAreRejected(String name, String defect) throws Exception {
        String token =
                switch (defect) {
                    case "issuer" -> signed(signingKey, "http://evil.test/realms/x", AUDIENCE, 300);
                    case "audience" -> signed(signingKey, ISSUER, "another-api", 300);
                    case "expired" -> signed(signingKey, ISSUER, AUDIENCE, -3600);
                    case "signature" -> signed(otherKey, ISSUER, AUDIENCE, 300);
                    default -> new PlainJWT(claims(ISSUER, AUDIENCE, 300)).serialize();
                };

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .satisfies(e -> {
                    if (defect.equals("audience")) {
                        assertThat(e).hasMessageContaining("audience");
                    }
                });
    }
}
