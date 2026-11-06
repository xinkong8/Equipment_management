package com.equipmentmanagement.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationHours;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-hours}") long expirationHours) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥至少需要 32 个字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
    }

    public String create(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("username", user.username())
                .claim("name", user.name())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public AppUser parse(String token) {
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AppUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class),
                claims.get("name", String.class), claims.get("role", String.class), "ACTIVE");
    }
}
