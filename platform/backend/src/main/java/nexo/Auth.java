package nexo;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class Auth {
    private final Accounts accounts;
    private final PasswordEncoder passwords;
    private final Sessions sessions;
    private final JwtEncoder encoder;
    private final StringRedisTemplate redis;
    private final ResetMail mail;
    private final boolean secure;
    private final String dummy;

    Auth(Accounts accounts, PasswordEncoder passwords, Sessions sessions, JwtEncoder encoder, StringRedisTemplate redis,
         ResetMail mail, @Value("${nexo.cookie-secure}") boolean secure) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.encoder = encoder;
        this.redis = redis;
        this.mail = mail;
        this.secure = secure;
        this.dummy = passwords.encode(UUID.randomUUID().toString());
    }

    record Credentials(String email, String password) {}
    record Reset(String token, String password) {}
    record Profile(UUID id, String email, String role) {
        Profile(Account account) { this(account.id, account.email, account.role); }
    }

    @PostMapping("/login")
    Profile login(@RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) throws JsonProcessingException {
        var email = email(input.email());
        throttle("login:ip:" + request.getRemoteAddr(), 35);
        throttle("login:account:" + digest(email), 12);
        var account = accounts.findByEmail(email).orElse(null);
        var candidate = input.password() == null || input.password().getBytes(StandardCharsets.UTF_8).length > 72 ? "" : input.password();
        boolean match = passwords.matches(candidate, account == null ? dummy : account.passwordHash);
        if (!match || account == null || !account.enabled) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
        var device = sessions.start(account, request.getHeader("User-Agent"));
        var claims = JwtClaimsSet.builder().issuer("nexo-iptv").subject(account.id.toString()).id(device.id())
            .issuedAt(device.createdAt()).expiresAt(device.expiresAt()).claim("role", account.role).build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        cookie(response, token, Sessions.TTL);
        return new Profile(account);
    }

    @GetMapping("/me")
    Profile me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.findById(UUID.fromString(jwt.getSubject())).filter(account -> account.enabled)
            .map(Profile::new).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    @PostMapping("/logout")
    void logout(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        sessions.revoke(jwt.getId());
        cookie(response, "", Duration.ZERO);
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgot(@RequestBody Credentials input, HttpServletRequest request) {
        var email = email(input.email());
        throttle("forgot:ip:" + request.getRemoteAddr(), 12);
        throttle("forgot:account:" + digest(email), 4);
        if (!mail.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A recuperação por e-mail está indisponível.");
        mail.send(email);
    }

    @PostMapping("/reset")
    void reset(@RequestBody Reset request, HttpServletRequest http) {
        throttle("reset:ip:" + http.getRemoteAddr(), 16);
        validatePassword(request.password());
        if (request.token() == null || !request.token().matches("[A-Za-z0-9_-]{43}")) throw ApiErrors.bad("Link inválido ou expirado.");
        var id = redis.opsForValue().getAndDelete("nexo:reset:" + digest(request.token()));
        if (id == null) throw ApiErrors.bad("Link inválido ou expirado.");
        var account = accounts.findById(UUID.fromString(id.split(":", 2)[0])).filter(person -> person.enabled)
            .orElseThrow(() -> ApiErrors.bad("Link inválido ou expirado."));
        if (!id.equals(account.id + ":" + digest(account.passwordHash))) throw ApiErrors.bad("Link inválido ou expirado.");
        account.passwordHash = passwords.encode(request.password());
        accounts.save(account);
        sessions.revokeAll(account.id);
    }

    private void throttle(String id, int limit) {
        var key = "nexo:limit:" + id;
        redis.opsForValue().setIfAbsent(key, "0", Duration.ofMinutes(15));
        var attempts = redis.opsForValue().increment(key);
        if (attempts != null && attempts > limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Tente novamente em alguns minutos.");
    }

    private void cookie(HttpServletResponse response, String value, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(Security.COOKIE, value).httpOnly(true)
            .secure(secure).sameSite("Strict").path("/").maxAge(age).build().toString());
    }

    static String email(String value) {
        var email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw ApiErrors.bad("Informe um e-mail válido.");
        return email;
    }

    static void validatePassword(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiErrors.bad("Use uma senha com pelo menos 12 caracteres e até 72 bytes.");
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

@Service
class ResetMail {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ResetMail.class);
    private final Accounts accounts;
    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> sender;
    private final String origin;
    private final String from;
    private final String host;
    private final SecureRandom random = new SecureRandom();

    ResetMail(Accounts accounts, StringRedisTemplate redis, ObjectProvider<JavaMailSender> sender,
              @Value("${nexo.public-origin}") String origin, @Value("${nexo.mail-from}") String from,
              @Value("${spring.mail.host}") String host) {
        this.accounts = accounts;
        this.redis = redis;
        this.sender = sender;
        this.origin = origin.replaceAll("/+$", "");
        this.from = from;
        this.host = host;
    }

    boolean available() { return !host.isBlank() && !from.isBlank() && sender.getIfAvailable() != null; }

    @Async
    public void send(String email) {
        accounts.findByEmail(email).filter(account -> account.enabled).ifPresent(account -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String key = "nexo:reset:" + Auth.digest(token);
            redis.opsForValue().set(key, account.id + ":" + Auth.digest(account.passwordHash), Duration.ofMinutes(20));
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("Redefinição de senha do Morera IPTV");
            message.setText("Abra o link em até 20 minutos:\n" + origin + "/redefinir#token=" + token);
            try { sender.getObject().send(message); }
            catch (org.springframework.mail.MailException error) {
                redis.delete(key);
                LOG.warn("Falha de entrega no serviço de recuperação de senha.");
            }
        });
    }
}

@Configuration
class InitialAdmin {
    @Bean ApplicationRunner bootstrap(Accounts accounts, PasswordEncoder passwords,
                                       @Value("${nexo.admin-email}") String email, @Value("${nexo.admin-password}") String password) {
        return args -> {
            if (email.isBlank() && password.isBlank()) return;
            var normalized = Auth.email(email);
            if (accounts.findByEmail(normalized).isEmpty()) {
                Auth.validatePassword(password);
                accounts.save(new Account(normalized, passwords.encode(password), "ADMIN"));
            }
        };
    }
}

