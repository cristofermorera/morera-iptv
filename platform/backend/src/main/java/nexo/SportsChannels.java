package nexo;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class SportsChannels {
    private static final Pattern VARIANT = Pattern.compile("(?:[ -]+(?:full hd|fullhd|ultra hd|fhd|uhd|hd|sd|4k|backup|alternativo)|\\s*\\([ab]\\)|\\s*\\[\\s*(?:full hd|fhd|uhd|hd|sd|4k|backup)\\s*\\])$");
    private static final Map<String, String> DEFAULTS = Map.of(
        "premiere clubes", "premiere", "premiere fc", "premiere", "disney+ premium", "disney+",
        "disney plus", "disney+", "amazon prime video", "prime video", "rede globo", "tv globo");
    private final JdbcTemplate db;

    SportsChannels(JdbcTemplate db) { this.db = db; }

    record Alias(String alias, String broadcaster) {}
    record Override(UUID channelId, String name, String group, boolean allowed) {}
    record Option(UUID id, String name, String group, String type, boolean favorite, String tvgId, UUID listId) {}

    int leadMinutes() {
        return db.queryForList("select value from platform_setting where name='sports.leadMinutes'", String.class)
            .stream().findFirst().map(Integer::parseInt).orElse(30);
    }

    void leadMinutes(int minutes) {
        if (minutes < 0 || minutes > 120) throw ApiErrors.bad("Informe uma antecedência de 0 a 120 minutos.");
        db.update("insert into platform_setting(name,value) values ('sports.leadMinutes',?) on conflict(name) do update set value=excluded.value", Integer.toString(minutes));
    }

    List<Alias> aliases() {
        return db.query("select alias,broadcaster from sports_channel_alias order by alias",
            (rows, index) -> new Alias(rows.getString(1), rows.getString(2)));
    }

    void saveAlias(Alias input) {
        String alias = key(input.alias()), broadcaster = key(input.broadcaster());
        if (alias.isBlank() || broadcaster.isBlank() || alias.length() > 140 || broadcaster.length() > 140)
            throw ApiErrors.bad("Preencha o apelido e a emissora.");
        db.update("insert into sports_channel_alias values (?,?) on conflict(alias) do update set broadcaster=excluded.broadcaster", alias, broadcaster);
    }

    void deleteAlias(String alias) { db.update("delete from sports_channel_alias where alias=?", key(alias)); }

    List<Override> overrides(String event) {
        return db.query("""
            select c.id,c.name,c.category,s.allowed from sports_channel_override s join channel c on c.id=s.channel_id
            where s.event_id=? order by c.name
            """, (r, n) -> new Override(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getBoolean(4)), event);
    }

    void override(String event, UUID channel, boolean allow) {
        if (event == null || event.isBlank() || event.length() > 260 || channel == null) throw ApiErrors.bad("Evento ou canal inválido.");
        int changed = db.update("""
            insert into sports_channel_override(event_id,channel_id,allowed) select ?,id,? from channel where id=? and kind='live'
            on conflict(event_id,channel_id) do update set allowed=excluded.allowed
            """, event, allow, channel);
        if (changed == 0) throw ApiErrors.missing();
    }

    void deleteOverride(String event, UUID channel) {
        db.update("delete from sports_channel_override where event_id=? and channel_id=?", event, channel);
    }

    record IdentifiedChannel(Option option, String broadcaster) {}
    record Owned(UUID user, List<IdentifiedChannel> channels, Map<String, String> aliases) {}

    Owned owned(UUID user) {
        var aliases = new HashMap<>(DEFAULTS);
        aliases().forEach(alias -> aliases.put(alias.alias(), alias.broadcaster()));
        var channels = db.query("""
            select c.id,c.name,c.category,c.kind,f.channel_id is not null,c.tvg_id,p.id
            from channel c join playlist p on p.id=c.playlist_id
            left join favorite f on f.channel_id=c.id and f.account_id=?
            where p.owner_id=? and c.kind='live' order by p.created_at desc,c.position,c.id
            """, (r, n) -> new Option(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),
                r.getBoolean(5),r.getString(6),r.getObject(7, UUID.class)), user, user);
        return new Owned(user, channels.stream().map(channel -> new IdentifiedChannel(channel, canonical(channel.name(), aliases))).toList(), aliases);
    }

    Map<String, Map<UUID, Boolean>> corrections(Owned owner) {
        var result = new HashMap<String, Map<UUID, Boolean>>();
        db.query("""
            select s.event_id,s.channel_id,s.allowed from sports_channel_override s
            join channel c on c.id=s.channel_id join playlist p on p.id=c.playlist_id where p.owner_id=?
            """, row -> {
                result.computeIfAbsent(row.getString(1), id -> new HashMap<>()).put(row.getObject(2, UUID.class), row.getBoolean(3));
            }, owner.user());
        return result;
    }

    List<Option> identify(Owned owner, Map<UUID, Boolean> manual, List<String> broadcasters) {
        var desired = new HashSet<String>();
        for (String label : broadcasters) for (String part : label.split("[/|]")) {
            String value = canonical(part, owner.aliases());
            if (!value.isBlank()) desired.add(value);
        }
        var choices = new ArrayList<Option>();
        for (IdentifiedChannel channel : owner.channels()) {
            if (manual.getOrDefault(channel.option().id(), desired.contains(channel.broadcaster()))) choices.add(channel.option());
        }
        return choices;
    }

    static String canonical(String value, Map<String, String> aliases) {
        String key = key(value);
        for (int i = 0; i < 8; i++) {
            String stripped = VARIANT.matcher(key).replaceFirst("").strip();
            if (stripped.equals(key)) break;
            key = stripped;
        }
        key = key(key);
        return aliases.getOrDefault(key, key);
    }

    private static String key(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ")
            .replaceAll("^(?:\\|\\s*br\\s*\\|\\s*)", "").replaceAll("^espn[ -]*([0-9]+)$", "espn $1");
    }
}

@RestController
class SportsBroadcastApi {
    private final GamesGuide guide;
    private final FootballScores scores;
    private final SportsChannels channels;

    SportsBroadcastApi(GamesGuide guide, FootballScores scores, SportsChannels channels) {
        this.guide = guide; this.scores = scores; this.channels = channels;
    }

    record Event(String id, String title, String sport, String league, String round, Instant kickoff, String status,
                 List<String> channels, FootballScores.Score score, Instant opensAt, int availableChannels) {}
    record Board(LocalDate date, Instant updatedAt, Instant now, boolean stale, String source, String scoreError,
                 int leadMinutes, List<Event> games) {}
    record Review(Event event, List<SportsChannels.Option> matches, List<SportsChannels.Override> corrections) {}
    record Window(int minutes) {}
    record Correction(String event, UUID channel, boolean allowed) {}

    @GetMapping("/api/sports/today")
    Board today(@AuthenticationPrincipal Jwt jwt) {
        var agenda = guide.today();
        var feed = scores.today(agenda.date());
        var owner = channels.owned(CatalogApi.user(jwt));
        var overrides = channels.corrections(owner);
        int lead = channels.leadMinutes();
        var games = agenda.games().stream().map(game -> event(game, feed, lead, options(game, owner, overrides).size()))
            .sorted(Comparator.comparing(Event::kickoff, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Event::title)).toList();
        return new Board(agenda.date(), agenda.updatedAt(), Instant.now(), agenda.stale(), agenda.source(), feed.error(), lead, games);
    }

    @GetMapping("/api/sports/options")
    List<SportsChannels.Option> playback(@AuthenticationPrincipal Jwt jwt, @RequestParam String event) {
        var review = review(CatalogApi.user(jwt), event);
        var game = review.event();
        if (review.matches().isEmpty() || !open(game, Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A janela de transmissão está fechada.");
        return review.matches();
    }

    @GetMapping("/api/admin/sports/review")
    Review adminReview(@AuthenticationPrincipal Jwt jwt, @RequestParam String event) {
        return review(CatalogApi.user(jwt), event);
    }

    @GetMapping("/api/admin/sports/aliases") List<SportsChannels.Alias> aliases() { return channels.aliases(); }
    @PutMapping("/api/admin/sports/aliases") void alias(@RequestBody SportsChannels.Alias alias) { channels.saveAlias(alias); }
    @DeleteMapping("/api/admin/sports/aliases") void deleteAlias(@RequestParam String alias) { channels.deleteAlias(alias); }
    @PutMapping("/api/admin/sports/window") void window(@RequestBody Window input) { channels.leadMinutes(input.minutes()); }
    @PutMapping("/api/admin/sports/override") void override(@RequestBody Correction input) { channels.override(input.event(), input.channel(), input.allowed()); }
    @DeleteMapping("/api/admin/sports/override") void deleteOverride(@RequestParam String event, @RequestParam UUID channel) { channels.deleteOverride(event, channel); }

    private Review review(UUID user, String id) {
        var agenda = guide.today();
        var game = agenda.games().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(ApiErrors::missing);
        var owner = channels.owned(user);
        var options = options(game, owner, channels.corrections(owner));
        return new Review(event(game, scores.today(agenda.date()), channels.leadMinutes(), options.size()), options, channels.overrides(id));
    }

    private List<SportsChannels.Option> options(GamesGuide.Game game, SportsChannels.Owned owner, Map<String, Map<UUID, Boolean>> overrides) {
        return channels.identify(owner, overrides.getOrDefault(game.id(), Map.of()), game.channels());
    }

    private Event event(GamesGuide.Game game, FootballScores.Feed feed, int lead, int count) {
        var score = FootballScores.match(game, feed.scores());
        String status = "scheduled".equals(game.status()) && score != null ? score.status() : game.status();
        Instant kickoff = score == null ? game.kickoff() : score.kickoff();
        return new Event(game.id(), game.title(), game.sport(), game.league(), game.round(), kickoff, status, game.channels(), score,
            kickoff == null ? null : kickoff.minus(Duration.ofMinutes(lead)), count);
    }

    static boolean open(Event event, Instant now) {
        if (List.of("live", "halftime").contains(event.status())) return true;
        return "scheduled".equals(event.status()) && event.opensAt() != null
            && !now.isBefore(event.opensAt()) && now.isBefore(event.kickoff());
    }
}


