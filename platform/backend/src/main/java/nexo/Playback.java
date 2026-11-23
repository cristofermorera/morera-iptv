package nexo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class Playback {
    private static final Pattern URI = Pattern.compile("\\bURI=\"([^\"]+)\"");
    private static final int MANIFEST_LIMIT = 2 * 1024 * 1024;
    private final Catalog catalog;
    private final Upstream upstream;
    private final NetworkSettings network;
    private final StringRedisTemplate redis;
    private final SourceSecrets secrets;
    private final HostRoute route;

    Playback(Catalog catalog, Upstream upstream, NetworkSettings network, StringRedisTemplate redis, SourceSecrets secrets, HostRoute route) {
        this.catalog = catalog;
        this.upstream = upstream;
        this.network = network;
        this.redis = redis;
        this.secrets = secrets;
        this.route = route;
    }

    record Source(String url, boolean hls) {}

    @GetMapping("/api/playback/{channel}")
    ResponseEntity<Source> source(@PathVariable UUID channel, @AuthenticationPrincipal Jwt jwt) throws IOException {
        var target = catalog.target(CatalogApi.user(jwt), channel);
        var policy = network.policy();
        route.require(policy);
        String path = target.url().encodedPath().toLowerCase(Locale.ROOT);
        boolean hls = path.endsWith(".m3u8") || path.endsWith(".m3u");
        if (target.browserPlayback() && "direct".equals(policy.hostMode())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Source(target.url().toString(), hls));
        }
        if (!hls && !path.matches(".*\\.(mp4|webm|mkv|mp3|aac|ts)$")) {
            try (var response = upstream.get(target.url(), (NetworkSettings.allowed(target.dns(), policy) ? target.dns() : policy.defaultDns()), "bytes=0-1023", Duration.ofSeconds(15))) {
                if (!response.isSuccessful()) throw ApiErrors.bad("A fonte de vídeo retornou HTTP " + response.code() + ".");
                hls = isManifest(response.header("Content-Type"), response.request().url());
                if (!hls && response.body() != null) {
                    var hint = response.body().source().peek().readByteString(Math.min(response.body().contentLength() < 0 ? 7 : response.body().contentLength(), 7));
                    hls = hint.utf8().startsWith("#EXTM3U");
                    if (hls) redis.opsForValue().set("nexo:hls:" + jwt.getId() + ":" + channel, "1", Duration.ofMinutes(10));
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Source("/media/" + channel, hls));
    }

    @GetMapping({"/media/{channel}", "/media/{channel}/{part}"})
    void stream(@PathVariable UUID channel, @PathVariable(required = false) String part, @AuthenticationPrincipal Jwt jwt,
                HttpServletRequest request, HttpServletResponse response) throws IOException {
        var target = catalog.target(CatalogApi.user(jwt), channel);
        var policy = network.policy();
        route.require(policy);
        var root = "nexo:media:" + jwt.getId() + ":" + channel + ":";
        HttpUrl url = target.url();
        if (part != null) {
            if (!part.matches("[0-9a-f]{64}")) throw ApiErrors.missing();
            String encoded = redis.opsForValue().get(root + part);
            if (encoded == null) throw ApiErrors.missing();
            url = upstream.url(secrets.open(encoded));
        }
        try (var source = upstream.get(url, (NetworkSettings.allowed(target.dns(), policy) ? target.dns() : policy.defaultDns()), request.getHeader("Range"), Duration.ofHours(8))) {
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (!source.isSuccessful() || source.body() == null) {
                response.setStatus(source.code());
                return;
            }
            String type = source.header("Content-Type", "application/octet-stream");
            HttpUrl base = source.request().url();
            if (isManifest(type, base) || (part == null && Boolean.TRUE.equals(redis.hasKey("nexo:hls:" + jwt.getId() + ":" + channel)))) {
                byte[] bytes = source.body().byteStream().readNBytes(MANIFEST_LIMIT + 1);
                if (bytes.length > MANIFEST_LIMIT) throw ApiErrors.bad("Manifesto HLS acima do limite.");
                StringBuilder manifest = new StringBuilder();
                for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                    String value = line.strip();
                    if (value.startsWith("#")) {
                        var match = URI.matcher(value);
                        value = match.replaceAll(result -> Matcher.quoteReplacement("URI=\"" + resource(root, channel, base, result.group(1)) + "\""));
                    } else if (!value.isBlank()) value = resource(root, channel, base, value);
                    manifest.append(value).append('\n');
                }
                response.setContentType("application/vnd.apple.mpegurl");
                response.getOutputStream().write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                response.setStatus(source.code());
                response.setContentType(type.startsWith("video/") || type.startsWith("audio/") ? type : "application/octet-stream");
                for (String header : new String[]{"Accept-Ranges", "Content-Range", "Content-Length"}) {
                    if (source.header(header) != null) response.setHeader(header, source.header(header));
                }
                source.body().byteStream().transferTo(response.getOutputStream());
            }
        } catch (IOException error) {
            if (!response.isCommitted()) response.sendError(502);
        }
    }

    private String resource(String root, UUID channel, HttpUrl base, String reference) {
        HttpUrl resolved = base.resolve(reference);
        if (resolved == null || !resolved.username().isEmpty() || !resolved.password().isEmpty())
            throw ApiErrors.bad("O manifesto contém uma referência inválida.");
        String source = resolved.toString();
        String id = Auth.digest(source);
        redis.opsForValue().set(root + id, secrets.seal(source), Duration.ofHours(2));
        return "/media/" + channel + "/" + id;
    }

    private static boolean isManifest(String type, HttpUrl url) {
        return (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl"))
            || url.encodedPath().toLowerCase(Locale.ROOT).matches(".*\\.m3u8?$");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<java.util.Map<String, String>> unavailable(IOException error) {
        return ResponseEntity.status(502).body(java.util.Map.of("error", "A fonte de vídeo não respondeu."));
    }
}


