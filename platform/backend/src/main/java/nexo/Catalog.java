package nexo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class Catalog {
    static final long LIMIT = 1024L * 1024 * 1024;
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private final JdbcTemplate db;
    private final Upstream upstream;
    private final SourceSecrets secrets;
    private final NetworkSettings network;
    private final HostRoute route;

    Catalog(JdbcTemplate db, Upstream upstream, SourceSecrets secrets, NetworkSettings network, HostRoute route) {
        this.db = db; this.upstream = upstream; this.secrets = secrets; this.network = network; this.route = route;
    }

    record Playlist(UUID id, String label, String dns, Instant createdAt) {}
    record Entry(UUID id, String name, String group, String type, boolean favorite, String tvgId) {}
    record Group(String name, long count) {}
    record Page(List<Entry> entries, long total) {}
    record Source(String mode, String url, String username, String password, String dns, String label) {}
    record Target(HttpUrl url, String dns, boolean browserPlayback) {}

    List<Playlist> playlists(UUID owner) {
        return db.query("select id,label,dns,created_at from playlist where owner_id=? order by created_at desc",
            (r, n) -> new Playlist(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getTimestamp(4).toInstant()), owner);
    }

    @Transactional(rollbackFor = Exception.class)
    public UUID fromUrl(UUID user, Source source) throws IOException {
        var url = upstream.url(source.url());
        if ("xtream".equals(source.mode())) {
            if (source.username() == null || source.username().isBlank() || source.password() == null || source.password().isEmpty())
                throw ApiErrors.bad("Informe o usuário e a senha.");
            url = url.newBuilder().encodedPath(url.encodedPath().replaceAll("/+$", "") + "/get.php").query(null)
                .addQueryParameter("username", source.username()).addQueryParameter("password", source.password())
                .addQueryParameter("type", "m3u_plus").addQueryParameter("output", "m3u8").build();
        } else if (!"url".equals(source.mode())) throw ApiErrors.bad("Tipo de fonte inválido.");
        route.require(network.policy());
        var dns = importDns(user, source.dns());
        try (var response = upstream.get(url, dns, null, Duration.ofMinutes(15))) {
            if (!response.isSuccessful() || response.body() == null)
                throw ApiErrors.bad("A fonte retornou HTTP " + response.code() + ".");
            return parse(user, source.label() == null ? url.host() : source.label(), dns, response.body().byteStream(), response.request().url());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public UUID fromFile(UUID user, InputStream input, long length, String dns, String label) throws IOException {
        if (length > LIMIT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "A lista excede o limite de 1 GB.");
        return parse(user, label, importDns(user, dns), input, null);
    }

    private String importDns(UUID user, String requested) {
        var policy = network.policy();
        if (requested == null) return network.preference(user, policy).dns();
        if (!NetworkSettings.allowed(requested, policy)) throw ApiErrors.bad("Este DNS não está autorizado.");
        return requested;
    }

    private UUID parse(UUID user, String label, String dns, InputStream input, HttpUrl base) throws IOException {
        UUID playlist = UUID.randomUUID();
        db.update("insert into playlist values (?,?,?,?,?)", playlist, user, text(label, "Lista M3U", 250), dns, Timestamp.from(Instant.now()));
        try (var lines = new PlaylistLines(input)) {
            String line;
            do { line = lines.next(); } while (line != null && line.replace("\uFEFF", "").isBlank());
            if (line == null || !line.replace("\uFEFF", "").strip().startsWith("#EXTM3U")) throw ApiErrors.bad("O conteúdo não é uma lista M3U válida.");
            var batch = new ArrayList<Object[]>(500);
            int position = 0;
            String name = null, category = null, tvgId = null;
            while ((line = lines.next()) != null) {
                line = line.strip();
                if (line.startsWith("#EXT-X-")) {
                    if (base == null) throw ApiErrors.bad("Para uma transmissão HLS, informe o link M3U8 original.");
                    db.update("delete from channel where playlist_id=?", playlist);
                    batch.clear();
                    batch.add(row(playlist, base, "Transmissão HLS", null, null, 1));
                    position = 1;
                    break;
                }
                if (line.startsWith("#EXTINF:")) {
                    int separator = separator(line);
                    name = separator < 0 ? null : line.substring(separator + 1);
                    category = null;
                    tvgId = null;
                    var matcher = ATTRIBUTE.matcher(separator < 0 ? line : line.substring(0, separator));
                    while (matcher.find()) {
                        if ("group-title".equalsIgnoreCase(matcher.group(1))) category = matcher.group(2);
                        if ("tvg-id".equalsIgnoreCase(matcher.group(1))) tvgId = matcher.group(2);
                    }
                } else if (line.startsWith("#EXTGRP:")) {
                    if (category == null || category.isBlank()) category = line.substring(8);
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    var target = base == null ? HttpUrl.parse(line) : base.resolve(line);
                    if (target != null && target.username().isEmpty() && target.password().isEmpty()) {
                        if (++position > 1_000_000) throw ApiErrors.bad("A lista excede o limite de um milhão de itens.");
                        batch.add(row(playlist, target, name, category, tvgId, position));
                        if (batch.size() == 500) { insert(batch); batch.clear(); }
                    }
                    name = category = tvgId = null;
                }
            }
            if (position == 0) throw ApiErrors.bad("A lista não contém canais com endereços válidos.");
            insert(batch);
        }
        return playlist;
    }

    private void insert(List<Object[]> batch) {
        if (!batch.isEmpty()) db.batchUpdate("insert into channel(id,playlist_id,name,category,kind,source,position,tvg_id) values (?,?,?,?,?,?,?,?)", batch);
    }

    private Object[] row(UUID list, HttpUrl target, String name, String category, String tvg, int position) {
        String path = target.encodedPath().toLowerCase(Locale.ROOT);
        var kind = path.contains("/series/") ? "series" : path.contains("/movie/") || path.matches(".*\\.(mp4|webm|mkv)$") ? "movies" : "live";
        return new Object[]{UUID.randomUUID(), list, text(name, "Canal " + position, 512), text(category, "Outros", 512),
            kind, secrets.seal(target.toString()), position, tvg == null ? null : text(tvg, "", 512)};
    }

    Page entries(UUID user, UUID list, String view, String category, String query, int page) {
        var args = new ArrayList<Object>(List.of(user, list, user));
        String filter = scope(view, category, query, args);
        String sql = " from channel c join playlist p on p.id=c.playlist_id left join favorite f on f.channel_id=c.id and f.account_id=? where p.id=? and p.owner_id=?" + filter;
        long count = db.queryForObject("select count(*)" + sql, Long.class, args.toArray());
        args.add(Math.max(0, (long) page) * 100);
        var items = db.query("select c.id,c.name,c.category,c.kind,f.channel_id is not null,c.tvg_id" + sql + " order by c.position limit 100 offset ?",
            (r, n) -> new Entry(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5), r.getString(6)), args.toArray());
        return new Page(items, count);
    }

    List<Group> groups(UUID user, UUID list, String view) {
        var args = new ArrayList<Object>(List.of(user, list, user));
        String count = "series".equals(view) ? "count(distinct c.series_key)" : "count(*)";
        return db.query("select c.category," + count + " from channel c join playlist p on p.id=c.playlist_id left join favorite f on f.channel_id=c.id and f.account_id=? where p.id=? and p.owner_id=?"
            + scope(view, "", "", args) + " group by c.category order by lower(c.category),c.category",
            (r, n) -> new Group(r.getString(1), r.getLong(2)), args.toArray());
    }

    private static String scope(String view, String category, String query, List<Object> args) {
        var condition = new StringBuilder();
        if ("favorites".equals(view)) condition.append(" and f.channel_id is not null");
        else if (List.of("live", "movies", "series").contains(view)) { condition.append(" and c.kind=?"); args.add(view); }
        if (!category.isBlank()) { condition.append(" and c.category=?"); args.add(category); }
        if (!query.isBlank()) {
            condition.append(" and strpos(lower(c.name || ' ' || c.category), lower(?))>0");
            args.add(text(query, "", 250));
        }
        return condition.toString();
    }

    Target target(UUID user, UUID channel) {
        return db.query("select c.source,p.dns,c.browser_playback from channel c join playlist p on c.playlist_id=p.id where c.id=? and p.owner_id=?",
            (r, n) -> new Target(upstream.url(secrets.open(r.getString(1))), r.getString(2), r.getBoolean(3)), channel, user)
            .stream().findFirst().orElseThrow(ApiErrors::missing);
    }

    void favorite(UUID owner, UUID channel, boolean enabled) {
        if (enabled) {
            db.update("""
                insert into favorite(account_id,channel_id)
                select ?, c.id from channel c join playlist p on p.id=c.playlist_id where c.id=? and p.owner_id=?
                on conflict do nothing
                """, owner, channel, owner);
        } else db.update("delete from favorite where account_id=? and channel_id=?", owner, channel);
    }

    static String text(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.strip().replace("\u0000", "");
        int end = clean.offsetByCodePoints(0, Math.min(clean.codePointCount(0, clean.length()), max));
        return clean.substring(0, end);
    }

    private static int separator(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') quoted = !quoted;
            else if (character == ',' && !quoted) return i;
        }
        return -1;
    }

    private static class PlaylistLines implements AutoCloseable {
        private final Reader reader;
        private final char[] buffer = new char[16384];
        private int offset, end;
        PlaylistLines(InputStream input) {
            reader = new InputStreamReader(new FilterInputStream(input) {
                long bytes;
                private void count(int read) throws IOException {
                    if (read > 0 && (bytes += read) > LIMIT)
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "A lista excede o limite de 1 GB.");
                }
                @Override public int read(byte[] b, int from, int length) throws IOException {
                    int read = in.read(b, from, length); count(read); return read;
                }
                @Override public int read() throws IOException {
                    int value = in.read(); count(value < 0 ? 0 : 1); return value;
                }
            }, StandardCharsets.UTF_8);
        }
        String next() throws IOException {
            var line = new StringBuilder();
            while (true) {
                if (offset == end) {
                    end = reader.read(buffer); offset = 0;
                    if (end < 0) { end = 0; return line.isEmpty() ? null : line.toString(); }
                }
                int start = offset;
                while (offset < end && buffer[offset] != '\n') offset++;
                if (line.length() + offset - start > 4 * 1024 * 1024) throw ApiErrors.bad("A lista contém uma linha maior que 4 MB.");
                line.append(buffer, start, offset - start);
                if (offset < end) { offset++; return line.toString(); }
            }
        }
        @Override public void close() throws IOException { reader.close(); }
    }
}

@RestController
@RequestMapping("/api")
class CatalogApi {
    private final Catalog catalog;
    private final JdbcTemplate db;
    CatalogApi(Catalog catalog, JdbcTemplate db) { this.catalog = catalog; this.db = db; }
    record ImportResult(UUID id) {}

    @GetMapping("/lists")
    List<Catalog.Playlist> lists(@AuthenticationPrincipal Jwt jwt) { return catalog.playlists(user(jwt)); }

    @PostMapping(value = "/lists", consumes = "application/json")
    ImportResult url(@AuthenticationPrincipal Jwt jwt, @RequestBody Catalog.Source input) throws IOException {
        return new ImportResult(catalog.fromUrl(user(jwt), input));
    }

    @PostMapping(value = "/lists", consumes = "application/vnd.apple.mpegurl")
    ImportResult file(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String dns,
                      @RequestParam(required = false) String label, HttpServletRequest request) throws IOException {
        return new ImportResult(catalog.fromFile(user(jwt), request.getInputStream(), request.getContentLengthLong(), dns, label));
    }

    @GetMapping("/lists/{id}/entries")
    Catalog.Page entries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue = "all") String view,
                         @RequestParam(defaultValue = "") String group, @RequestParam(defaultValue = "") String q,
                         @RequestParam(defaultValue = "0") int page) {
        return catalog.entries(user(jwt), id, view, group, q, page);
    }

    @GetMapping("/lists/{id}/categories")
    List<Catalog.Group> categories(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue = "all") String view) {
        return catalog.groups(user(jwt), id, view);
    }

    @GetMapping("/lists/{id}/stats")
    Map<String, Long> stats(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return db.query("""
            select c.kind,case when c.kind='series' then count(distinct c.series_key) else count(*) end,count(f.channel_id)
            from channel c join playlist p on p.id=c.playlist_id left join favorite f on f.channel_id=c.id and f.account_id=?
            where p.id=? and p.owner_id=? group by c.kind
            """, result -> {
            var counts = new HashMap<>(Map.of("live", 0L, "movies", 0L, "series", 0L, "favorites", 0L));
            while (result.next()) {
                counts.put(result.getString(1), result.getLong(2));
                counts.merge("favorites", result.getLong(3), Long::sum);
            }
            return counts;
        }, user(jwt), id, user(jwt));
    }

    @GetMapping("/epg/{id}/now")
    List<Map<String, Object>> program(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return db.queryForList("""
            select e.title,e.starts_at,e.ends_at from epg_program e join channel c on c.tvg_id=e.channel_code
            join playlist p on p.id=c.playlist_id where c.id=? and p.owner_id=? and e.ends_at>now()
            order by e.starts_at limit 2
            """, id, user(jwt));
    }

    @GetMapping("/platform")
    Map<String, String> platform() {
        return Map.of("name", db.queryForList("select value from platform_setting where name='platform.name'", String.class)
            .stream().findFirst().orElse("Morera IPTV"));
    }

    @PutMapping("/favorites/{id}")
    void addFavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { catalog.favorite(user(jwt), id, true); }

    @DeleteMapping("/favorites/{id}")
    void removeFavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { catalog.favorite(user(jwt), id, false); }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, String> upstreamError(IOException error) {
        return Map.of("error", error instanceof SocketTimeoutException ? "Tempo limite ao carregar a lista." : "Falha ao ler a fonte IPTV. Confira o endereço, o DNS e a conexão.");
    }

    static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}




