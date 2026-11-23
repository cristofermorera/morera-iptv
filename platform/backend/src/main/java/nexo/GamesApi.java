package nexo;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class GamesApi {
    private final GamesGuide guide;

    GamesApi(GamesGuide guide) { this.guide = guide; }

    @GetMapping("/api/games/today")
    GamesGuide.Schedule today() { return guide.today(); }
}

@Service
class GamesGuide {
    private static final HttpUrl SOURCE = HttpUrl.get("https://sportune.com.br/jogos-de-hoje-na-tv");
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final int MAX_BYTES = 7 * 1024 * 1024;
    private final Upstream upstream;
    private final NetworkSettings network;
    private final HostRoute route;
    private final ObjectMapper json;
    private Schedule cache;
    private LocalDate attemptedDate;
    private Instant retryAfter = Instant.EPOCH;

    GamesGuide(Upstream upstream, NetworkSettings network, HostRoute route, ObjectMapper json) {
        this.upstream = upstream;
        this.network = network;
        this.route = route;
        this.json = json;
    }

    record Game(String id, String title, String sport, String league, String round, Instant kickoff, String status, List<String> channels) {}
    record Schedule(LocalDate date, Instant updatedAt, boolean stale, String source, List<Game> games) {}

    synchronized Schedule today() {
        var now = Instant.now();
        var date = LocalDate.ofInstant(now, BRASILIA);
        boolean usable = cache != null && date.equals(cache.date());
        if (usable && now.isBefore(cache.updatedAt().plus(CACHE_TTL))) return cache;
        if (date.equals(attemptedDate) && now.isBefore(retryAfter)) return staleOrUnavailable(usable);
        attemptedDate = date;
        retryAfter = now.plus(RETRY_DELAY);
        try {
            var policy = network.policy();
            route.require(policy);
            try (var response = upstream.get(SOURCE, policy.defaultDns(), null, Duration.ofSeconds(20))) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Agenda indisponível.");
                var stream = response.body().byteStream();
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Agenda acima do limite.");
                Document document = Jsoup.parse(new java.io.ByteArrayInputStream(bytes), "UTF-8", SOURCE.toString());
                cache = new Schedule(date, Instant.now(), false, SOURCE.toString(), parse(document, date));
                return cache;
            }
        } catch (IOException | ResponseStatusException error) {
            return staleOrUnavailable(usable);
        }
    }

    private Schedule staleOrUnavailable(boolean usable) {
        if (usable) return new Schedule(cache.date(), cache.updatedAt(), true, cache.source(), cache.games());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A agenda está indisponível. Tente novamente em instantes.");
    }

    private List<Game> parse(Document document, LocalDate date) throws IOException {
        for (Element script : document.select("script[type=application/ld+json]")) {
            JsonNode schema = json.readTree(script.data());
            if (schema == null || !"ItemList".equals(schema.path("@type").asText()) || !schema.path("itemListElement").isArray())
                continue;
            return events(schema.path("itemListElement"), document, date);
        }
        throw new IOException("Formato da agenda mudou.");
    }

    private List<Game> events(JsonNode items, Document document, LocalDate date) throws IOException {
        var result = new LinkedHashMap<String, Game>();
        for (JsonNode item : items) {
            JsonNode event = item.path("item");
            if (!"SportsEvent".equals(event.path("@type").asText())) continue;
            var rawDate = event.path("startDate").asText();
            Instant start = null;
            LocalDate day;
            try {
                if (rawDate.length() == 10) day = LocalDate.parse(rawDate);
                else {
                    start = OffsetDateTime.parse(rawDate).toInstant();
                    day = LocalDate.ofInstant(start, BRASILIA);
                }
            } catch (DateTimeException invalid) { continue; }
            if (!day.equals(date)) continue;

            String title = event.path("name").asText();
            String league = event.path("organizer").path("name").asText();
            String id = event.path("url").asText();
            if (title.isBlank() || id.isBlank()) continue;
            var channels = new LinkedHashSet<String>();
            for (JsonNode broadcast : event.path("subjectOf")) {
                if (!broadcast.path("isLiveBroadcast").asBoolean(false)) continue;
                var channel = broadcast.path("publishedOn").path("name").asText().strip();
                if (!channel.isBlank()) channels.add(channel);
            }
            String status = event.path("eventStatus").asText();
            status = status.endsWith("/EventCancelled") ? "cancelled"
                : status.endsWith("/EventPostponed") ? "postponed" : "scheduled";
            result.putIfAbsent(id, new Game(id, title, sport(document, id), league, round(document, id), start, status, List.copyOf(channels)));
        }
        if (result.isEmpty() && !items.isEmpty()) throw new IOException("A data de hoje não consta na fonte.");
        return result.values().stream()
            .sorted(Comparator.comparing(Game::kickoff, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Game::title))
            .toList();
    }

    private String round(Document document, String eventUrl) {
        int fragment = eventUrl.indexOf('#');
        var row = fragment < 0 ? null : document.getElementById(eventUrl.substring(fragment + 1));
        var cell = row == null ? null : row.selectFirst(".cartao-apoio");
        if (cell == null) return null;
        String[] parts = cell.text().split(" · ", 2);
        return parts.length == 2 ? parts[1] : null;
    }

    private String sport(Document document, String eventUrl) {
        int fragment = eventUrl.indexOf('#');
        var row = fragment < 0 ? null : document.getElementById(eventUrl.substring(fragment + 1));
        var section = row == null ? null : row.closest("section");
        var heading = section == null ? null : section.selectFirst("h2");
        if (heading == null) return "Outros esportes";
        String text = heading.text();
        int end = text.indexOf(" hoje:");
        return end > 0 ? text.substring(0, end).strip() : "Outros esportes";
    }
}

