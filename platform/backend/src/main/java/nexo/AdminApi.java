package nexo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
class AdminApi {
    private final AccountAdmin users;
    private final JdbcTemplate db;
    private final Sessions sessions;
    private final Upstream upstream;
    private final SourceSecrets secrets;
    private final Epg epg;

    AdminApi(AccountAdmin users, JdbcTemplate db, Sessions sessions, Upstream upstream, SourceSecrets secrets, Epg epg) {
        this.users = users; this.db = db; this.sessions = sessions; this.upstream = upstream; this.secrets = secrets; this.epg = epg;
    }

    record UserInput(String email, String password, String role, boolean enabled) {}
    record User(UUID id, String email, String role, boolean enabled) {
        User(Account account) { this(account.id, account.email, account.role, account.enabled); }
    }
    record Label(String label) {}
    record ChannelInput(String name, String group, String type, String tvgId, String url, UUID playlistId, boolean browserPlayback) {}
    record Category(UUID playlistId, String name, String replacement) {}
    record EpgInput(String label, String url) {}

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("account", "playlist", "channel", "epg_source"))
            result.put(table, db.queryForObject("select count(*) from " + table, Long.class));
        result.put("sessions", sessions.list().size());
        result.put("status", "Operacional");
        return result;
    }

    @GetMapping("/users") List<User> users() { return users.list(); }
    @PostMapping("/users") User create(@AuthenticationPrincipal Jwt jwt, @RequestBody UserInput data) {
        var person = users.create(data);
        audit(jwt, "USER_CREATED");
        return person;
    }
    @PutMapping("/users/{id}") User update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody UserInput data) {
        var person = users.update(CatalogApi.user(jwt), id, data);
        audit(jwt, "USER_UPDATED");
        return person;
    }
    @GetMapping("/sessions") List<Sessions.Device> devices() { return sessions.list(); }
    @DeleteMapping("/sessions/{id}") void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        if (!id.matches("[0-9a-f-]{36}")) throw ApiErrors.bad("Sessão inválida.");
        sessions.revoke(id); audit(jwt, "SESSION_REVOKED");
    }
    @GetMapping("/lists") List<Map<String, Object>> lists() {
        return db.queryForList("select p.id,p.label,p.dns,p.created_at,a.email from playlist p join account a on p.owner_id=a.id order by p.created_at desc limit 250");
    }
    @PutMapping("/lists/{id}") void renameList(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Label input) {
        if (db.update("update playlist set label=? where id=?", required(input.label(), 250), id) == 0) throw ApiErrors.missing();
        audit(jwt, "PLAYLIST_UPDATED");
    }
    @DeleteMapping("/lists/{id}") void deleteList(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        db.update("delete from playlist where id=?", id); audit(jwt, "PLAYLIST_DELETED");
    }
    @GetMapping("/channels") List<Map<String, Object>> channels(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int page,
                                                               @RequestParam(required=false) UUID playlist, @RequestParam(required=false) String kind) {
        String scope = playlist == null ? "" : " and p.id=?";
        var args = new ArrayList<Object>();
        args.add(Catalog.text(q, "", 250));
        if (playlist != null) args.add(playlist);
        if (kind != null) { scope += " and c.kind=?"; args.add(kind(kind)); }
        args.add(Math.max(0L, page) * 100);
        return db.queryForList("""
            select c.id,c.name,c.category,c.kind,c.tvg_id,c.browser_playback,p.label,p.id as playlist_id from channel c join playlist p on p.id=c.playlist_id
            where strpos(lower(c.name || ' ' || c.category),lower(?))>0
            """ + scope + " order by p.id,c.position,c.id limit 100 offset ?", args.toArray());
    }
    @PostMapping("/channels") @Transactional void createChannel(@AuthenticationPrincipal Jwt jwt, @RequestBody ChannelInput input) {
        if (input.playlistId() == null) throw ApiErrors.bad("Selecione uma lista.");
        UUID list = input.playlistId();
        var locked = db.queryForList("select id from playlist where id=? for update", UUID.class, list);
        if (locked.isEmpty()) throw ApiErrors.missing();
        long position = db.queryForObject("select coalesce(max(position),0)+1 from channel where playlist_id=?", Long.class, list);
        db.update("insert into channel(id,playlist_id,name,category,kind,source,position,tvg_id,browser_playback) values(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), list, required(input.name(),512), required(input.group(),512), kind(input.type()),
            secrets.seal(upstream.url(input.url()).toString()), position, Catalog.text(input.tvgId(), "", 512), browserPlayback(input));
        audit(jwt, "CHANNEL_CREATED");
    }
    @PutMapping("/channels/{id}") void editChannel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody ChannelInput input) {
        String target = input.url() == null || input.url().isBlank() ? null : secrets.seal(upstream.url(input.url()).toString());
        if (db.update("update channel set name=?, category=?,kind=?,tvg_id=?,source=coalesce(?,source),browser_playback=case when ? then ? else false end where id=?",
            required(input.name(), 512), required(input.group(), 512), kind(input.type()), Catalog.text(input.tvgId(), "", 512), target, input.browserPlayback(), target == null ? currentBrowserPlayback(id) : browserPlayback(input), id) == 0)
            throw ApiErrors.missing();
        audit(jwt, "CHANNEL_UPDATED");
    }
    @DeleteMapping("/channels/{id}") void deleteChannel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        db.update("delete from channel where id=?", id); audit(jwt, "CHANNEL_DELETED");
    }
    @PutMapping("/categories") void editCategory(@AuthenticationPrincipal Jwt jwt, @RequestBody Category input) {
        int changed = db.update("update channel set category=? where playlist_id=? and category=?",
            required(input.replacement(), 512), input.playlistId(), required(input.name(), 512));
        if (changed == 0) throw ApiErrors.missing();
        audit(jwt, "CATEGORY_RENAMED");
    }
    @GetMapping("/epg") List<Map<String,Object>> epgSources() {
        return db.queryForList("select id,label,updated_at from epg_source order by label");
    }
    @PostMapping("/epg") Map<String, UUID> createEpg(@AuthenticationPrincipal Jwt jwt, @RequestBody EpgInput input) {
        UUID id = UUID.randomUUID();
        db.update("insert into epg_source(id,label,source) values(?,?,?)", id, required(input.label(), 250), secrets.seal(upstream.url(input.url()).toString()));
        audit(jwt, "EPG_CREATED"); return Map.of("id", id);
    }
    @PostMapping("/epg/{id}/refresh") Map<String, Integer> refreshEpg(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        try {
            int imported = epg.refresh(id);
            audit(jwt, "EPG_REFRESHED"); return Map.of("programs", imported);
        } catch (java.io.IOException | javax.xml.stream.XMLStreamException error) { throw ApiErrors.bad("Falha ao ler a fonte XMLTV."); }
    }
    @DeleteMapping("/epg/{id}") void deleteEpg(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        db.update("delete from epg_source where id=?", id); audit(jwt, "EPG_DELETED");
    }
    @GetMapping("/settings") Map<String,String> settings() {
        return Map.of("name", db.queryForList("select value from platform_setting where name='platform.name'", String.class)
            .stream().findFirst().orElse("Morera IPTV"));
    }
    @PutMapping("/settings") void settings(@AuthenticationPrincipal Jwt jwt, @RequestBody Label input) {
        db.update("insert into platform_setting(name,value) values ('platform.name',?) on conflict(name) do update set value=excluded.value", required(input.label(), 80));
        audit(jwt, "PLATFORM_UPDATED");
    }
    @GetMapping("/logs") List<Map<String, Object>> logs() {
        return db.queryForList("select id,actor,action,happened_at from audit_event order by id desc limit 200");
    }

    private void audit(Jwt jwt, String action) {
        db.update("insert into audit_event(actor,action,happened_at) select email,?,? from account where id=?",
            action, Timestamp.from(Instant.now()), CatalogApi.user(jwt));
    }

    private String required(String value, int size) {
        var normalized = Catalog.text(value, "", size);
        if (normalized.isBlank()) throw ApiErrors.bad("Preencha todos os campos obrigatórios.");
        return normalized;
    }

    private boolean currentBrowserPlayback(UUID id) {
        return db.queryForList("select browser_playback from channel where id=?", Boolean.class, id)
            .stream().findFirst().orElse(false);
    }

    private boolean browserPlayback(ChannelInput input) {
        if (!input.browserPlayback()) return false;
        var url = upstream.url(input.url());
        if (!url.isHttps() || url.query() != null || !(url.encodedPath().endsWith(".m3u8") || url.encodedPath().matches(".*\\.(mp4|webm)$")))
            throw ApiErrors.bad("A reprodução direta exige uma mídia pública HTTPS sem parâmetros na URL.");
        return true;
    }

    private String kind(String value) {
        if (value == null || !List.of("live", "movies", "series").contains(value)) throw ApiErrors.bad("Tipo de mídia inválido.");
        return value;
    }
}

@org.springframework.stereotype.Service
class AccountAdmin {
    private final Accounts accounts;
    private final PasswordEncoder encoder;
    private final Sessions sessions;
    AccountAdmin(Accounts accounts, PasswordEncoder encoder, Sessions sessions) {
        this.accounts = accounts; this.encoder = encoder; this.sessions = sessions;
    }
    List<AdminApi.User> list() { return accounts.findAll().stream().map(AdminApi.User::new).sorted(Comparator.comparing(AdminApi.User::email)).toList(); }
    AdminApi.User create(AdminApi.UserInput input) {
        validateRole(input.role()); Auth.validatePassword(input.password());
        var account = new Account(Auth.email(input.email()), encoder.encode(input.password()), input.role());
        account.enabled = input.enabled();
        return save(account);
    }
    AdminApi.User update(UUID actor, UUID id, AdminApi.UserInput input) {
        validateRole(input.role());
        if (actor.equals(id) && (!input.enabled() || !"ADMIN".equals(input.role()))) throw ApiErrors.bad("Mantenha seu próprio acesso administrativo.");
        var account = accounts.findById(id).orElseThrow(ApiErrors::missing);
        account.email = Auth.email(input.email());
        account.role = input.role();
        account.enabled = input.enabled();
        if (input.password() != null && !input.password().isBlank()) {
            Auth.validatePassword(input.password()); account.passwordHash = encoder.encode(input.password());
        }
        var saved = save(account);
        sessions.revokeAll(id);
        return saved;
    }
    private AdminApi.User save(Account account) {
        try { return new AdminApi.User(accounts.saveAndFlush(account)); }
        catch (DataIntegrityViolationException error) { throw ApiErrors.bad("Este e-mail já está em uso."); }
    }
    private void validateRole(String role) {
        if (!"ADMIN".equals(role) && !"USER".equals(role)) throw ApiErrors.bad("Permissão inválida.");
    }
}



