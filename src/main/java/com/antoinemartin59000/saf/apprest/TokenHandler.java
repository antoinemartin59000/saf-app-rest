package com.antoinemartin59000.saf.apprest;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.SafServiceSession.ServiceSessionInitiatorType;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

public class TokenHandler {

    private static final SecretKey SECRET_KEY = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    private static final long EXPIRATION_MS = 3_600_000; // 1 hour

    public static String generateToken(ServiceSessionInitiatorType serviceSessionType, Long id) {

        Map<String, Object> claims = new HashMap<>();
        claims.put("sessionType", serviceSessionType.name());
        claims.put("id", id);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(SECRET_KEY)
                .compact();
    }

    public static SafServiceSession generateServiceSession(DataSource dataSource, String token) throws SafRestException {

        if (token == null) {
            return new SafServiceSession(dataSource, ServiceSessionInitiatorType.VISITOR, null);
        }

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            throw new SafRestException(401, "Invalid Token");
        }

        ServiceSessionInitiatorType sessionType = ServiceSessionInitiatorType.valueOf(claims.get("sessionType", String.class));
        Long id = claims.get("id", Long.class);

        return new SafServiceSession(dataSource, sessionType, id);
    }

}
