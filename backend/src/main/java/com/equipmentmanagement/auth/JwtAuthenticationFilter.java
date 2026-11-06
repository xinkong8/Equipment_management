package com.equipmentmanagement.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null && request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("equipment_access".equals(cookie.getName())) { token = cookie.getValue(); break; }
            }
        }
        if (token != null && !token.isBlank()) {
            try {
                AppUser tokenUser = jwtService.parse(token);
                AppUser user = jdbc.query("SELECT username, name, role, status FROM users WHERE id = ?", rs -> {
                    if (!rs.next()) return null;
                    return new AppUser(tokenUser.id(), rs.getString("username"), rs.getString("name"),
                            rs.getString("role"), rs.getString("status"));
                }, tokenUser.id());
                if (user == null || !"ACTIVE".equals(user.status())) throw new IllegalArgumentException("账号不可用");
                var authentication = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
