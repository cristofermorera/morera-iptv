package nexo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lists/{list}/series")
class SeriesApi {
    private final JdbcTemplate db;
    SeriesApi(JdbcTemplate db) { this.db = db; }

    record Series(String key, String title, long seasons, long episodes) {}
    record SeriesPage(List<Series> series, long total) {}
    record Episode(UUID id, String name, String group, String type, boolean favorite, String tvgId,
                   Integer season, Integer episode) {}

    @GetMapping
    SeriesPage series(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID list,
                      @RequestParam(defaultValue = "") String group, @RequestParam(defaultValue = "") String q,
                      @RequestParam(defaultValue = "0") int page) {
        var user = CatalogApi.user(jwt);
        var args = new ArrayList<Object>(List.of(list, user));
        String from = """
            from channel c join playlist p on p.id=c.playlist_id
            where p.id=? and p.owner_id=? and c.kind='series'
            """;
        if (!group.isBlank()) {
            from += " and c.category=?";
            args.add(group);
        }
        if (!q.isBlank()) {
            from += " and strpos(lower(c.name || ' ' || c.category), lower(?)) > 0";
            args.add(Catalog.text(q, "", 250));
        }
        long total = db.queryForObject("select count(distinct c.series_key) " + from, Long.class, args.toArray());
        args.add(Math.max(0L, page) * 100);
        var rows = db.query("""
            select c.series_key, min(case when c.episode_number is not null
                then coalesce(nullif(btrim((iptv_episode_parts(c.name))[1], ' -._'), ''), c.name) else c.name end),
                count(distinct c.season_number), count(*)
            """ + from + " group by c.series_key order by c.series_key limit 100 offset ?",
            (r, n) -> new Series(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4)), args.toArray());
        return new SeriesPage(rows, total);
    }

    @GetMapping("/episodes")
    List<Episode> episodes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID list,
                           @RequestParam String key, @RequestParam(defaultValue = "") String group) {
        var user = CatalogApi.user(jwt);
        var args = new ArrayList<Object>(List.of(user, list, user, key));
        String groupFilter = "";
        if (!group.isBlank()) {
            groupFilter = " and c.category=?";
            args.add(group);
        }
        return db.query("""
            select c.id,c.name,c.category,c.kind,f.channel_id is not null,c.tvg_id,c.season_number,c.episode_number
            from channel c join playlist p on p.id=c.playlist_id
            left join favorite f on f.channel_id=c.id and f.account_id=?
            where c.playlist_id=? and p.owner_id=? and c.kind='series' and c.series_key=?
            """ + groupFilter + " order by c.season_number nulls last,c.episode_number nulls last,c.position,c.id",
            (r,n) -> new Episode(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                r.getBoolean(5), r.getString(6), r.getObject(7,Integer.class), r.getObject(8,Integer.class)), args.toArray());
    }
}
