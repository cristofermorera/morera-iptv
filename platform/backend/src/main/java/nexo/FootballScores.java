package nexo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import okhttp3.HttpUrl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
class FootballScores {
    private final HttpUrl base = HttpUrl.get("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard");
    private final StringRedisTemplate redis;
    private final Upstream upstream;
    private final NetworkSettings network;
    private final HostRoute route;
    private final ObjectMapper json;
    private LocalDate lastAttemptDate;
    private Instant retryAfter = Instant.EPOCH;
    private String error;

    FootballScores(StringRedisTemplate redis, Upstream upstream, NetworkSettings network, HostRoute route, ObjectMapper json) {
        this.redis = redis; this.upstream = upstream; this.network = network; this.route = route; this.json = json;
    }

    record Team(String name, String badge, String score) {}
    record Score(String id, Team home, Team away, Instant kickoff, String status, String minute) {}
    record Feed(List<Score> scores, String error) {}

    synchronized Feed today(LocalDate date) {
        var key = "nexo:sports:scoreboard:" + date;
        var cached = redis.opsForValue().get(key);
        if (cached != null) {
            try { return new Feed(parse(json.readTree(cached)), null); }
            catch (IOException ignored) { redis.delete(key); }
        }
        if (date.equals(lastAttemptDate) && Instant.now().isBefore(retryAfter)) return new Feed(List.of(), error);
        lastAttemptDate = date;
        retryAfter = Instant.now().plusSeconds(60);
        try {
            var policy = network.policy();
            route.require(policy);
            var url = base.newBuilder().addQueryParameter("dates", date.format(DateTimeFormatter.BASIC_ISO_DATE))
                .addQueryParameter("limit", "250").build();
            try (var response = upstream.get(url, policy.defaultDns(), null, Duration.ofSeconds(6))) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Placar indisponível.");
                var bytes = response.body().byteStream().readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IOException("Placar acima do limite.");
                var body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                var scores = parse(json.readTree(body));
                redis.opsForValue().set(key, body, Duration.ofSeconds(55));
                retryAfter = Instant.EPOCH;
                error = null;
                return new Feed(scores, null);
            }
        } catch (IOException | org.springframework.web.server.ResponseStatusException unavailable) {
            error = "O placar da ESPN está temporariamente indisponível.";
            return new Feed(List.of(), error);
        }
    }

    private List<Score> parse(JsonNode root) throws IOException {
        if (root == null || !root.path("events").isArray()) throw new IOException("Formato do placar inesperado.");
        var scores = new ArrayList<Score>();
        for (var event : root.path("events")) {
            var match = event.path("competitions").path(0);
            var status = match.path("status");
            var type = status.path("type");
            String name = type.path("name").asText();
            String state = type.path("state").asText();
            String phase;
            if (name.contains("POSTPONED")) phase = "postponed";
            else if (name.contains("CANCEL")) phase = "cancelled";
            else if (name.contains("SUSPEND") || name.contains("ABANDON") || name.contains("DELAY")) phase = "suspended";
            else if (name.contains("HALFTIME")) phase = "halftime";
            else if ("in".equals(state)) phase = "live";
            else if ("post".equals(state) || type.path("completed").asBoolean()) phase = "finished";
            else if ("pre".equals(state)) phase = "scheduled";
            else continue;

            Team home = null, away = null;
            for (var item : match.path("competitors")) {
                var team = item.path("team");
                String teamName = team.path("displayName").asText();
                if (teamName.isBlank()) continue;
                String badge = team.path("logo").asText();
                if (!badge.startsWith("https://a.espncdn.com/")) badge = null;
                String value = List.of("live", "halftime", "finished").contains(phase) ? item.path("score").asText(null) : null;
                var competitor = new Team(teamName, badge, value == null || value.isBlank() ? null : value);
                if ("home".equals(item.path("homeAway").asText())) home = competitor;
                if ("away".equals(item.path("homeAway").asText())) away = competitor;
            }
            if (home == null || away == null) continue;
            String clock = "live".equals(phase) ? status.path("displayClock").asText(null) : null;
            if (clock != null && (clock.isBlank() || clock.equals("0'"))) clock = null;
            try {
                var kickoff = OffsetDateTime.parse(match.path("date").asText(event.path("date").asText())).toInstant();
                scores.add(new Score(event.path("id").asText(), home, away, kickoff, phase, clock));
            } catch (DateTimeException ignored) { /* The event has no usable date. */ }
        }
        return scores;
    }

    static Score match(GamesGuide.Game event, List<Score> scores) {
        if (!"Futebol".equalsIgnoreCase(event.sport()) || event.kickoff() == null) return null;
        String[] teams = event.title().split("\\s+[×x]\\s+", 2);
        if (teams.length != 2) return null;
        var same = scores.stream().filter(score -> teamKey(teams[0]).equals(teamKey(score.home().name()))
            && teamKey(teams[1]).equals(teamKey(score.away().name()))
            && LocalDate.ofInstant(event.kickoff(), ZoneId.of("America/Sao_Paulo"))
                .equals(LocalDate.ofInstant(score.kickoff(), ZoneId.of("America/Sao_Paulo")))).toList();
        return same.size() == 1 ? same.getFirst() : null;
    }

    private static String teamKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").strip()
            .replaceAll("\\s+(fc|cf|sc|ac|afc)$", "").replaceAll("\\s+", " ");
    }
}

