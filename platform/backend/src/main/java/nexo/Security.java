package nexo;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
    static final String COOKIE = "nexo_session";

    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }

    @Bean SecretKey jwtKey(@Value("${nexo.jwt-secret}") String value) {
        byte[] key = Base64.getDecoder().decode(value);
        if (key.length < 32) throw new IllegalArgumentException("JWT_SECRET deve ter pelo menos 32 bytes em Base64.");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean JwtEncoder encoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean JwtDecoder decoder(SecretKey key, Sessions sessions) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).build();
        var defaults = JwtValidators.createDefaultWithIssuer("nexo-iptv");
        decoder.setJwtValidator(jwt -> {
            var result = defaults.validate(jwt);
            if (result.hasErrors()) return result;
            return sessions.active(jwt.getId(), jwt.getSubject())
                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"));
        });
        return decoder;
    }

    @Bean SecurityFilterChain rules(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .addFilterBefore((request, response, chain) -> {
                var req = (jakarta.servlet.http.HttpServletRequest) request;
                if (!List.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
                    && !"1".equals(req.getHeader("X-Nexo-Request"))) {
                    ((jakarta.servlet.http.HttpServletResponse) response).sendError(403);
                    return;
                }
                chain.doFilter(request, response);
            }, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/forgot", "/api/auth/reset").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**", "/media/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/login", "/recuperar", "/redefinir", "/configuracoes", "/jogos", "/admin", "/admin/**").permitAll()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(req -> {
                if (List.of("/api/auth/login", "/api/auth/forgot", "/api/auth/reset").contains(req.getServletPath())) return null;
                return req.getCookies() == null ? null : Arrays.stream(req.getCookies())
                    .filter(c -> COOKIE.equals(c.getName())).findFirst().map(Cookie::getValue).orElse(null);
            }).jwt(jwt -> jwt.jwtAuthenticationConverter(token -> new JwtAuthenticationToken(token,
                List.of(new SimpleGrantedAuthority("ROLE_" + token.getClaimAsString("role")))))))
            .build();
    }
}




