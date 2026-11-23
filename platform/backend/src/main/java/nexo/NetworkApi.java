package nexo;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.HttpUrl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
class NetworkSettings {
    private final JdbcTemplate db;

    NetworkSettings(JdbcTemplate db) { this.db = db; }

    record Preference(String dns, String connection) {}
    record Policy(String defaultDns, boolean allowSystem, boolean allowCloudflare, String hostMode,
                  String wireGuardEndpoint, String wireGuardEgress) {}

    Policy policy() {
        var settings = db.query("select name,value from platform_setting where name like 'network.%'",
            result -> {
                var data = new java.util.HashMap<String, String>();
                while (result.next()) data.put(result.getString(1), result.getString(2));
                return data;
            });
        return new Policy(settings.getOrDefault("network.defaultDns", "cloudflare"),
            Boolean.parseBoolean(settings.getOrDefault("network.allowSystem", "true")),
            Boolean.parseBoolean(settings.getOrDefault("network.allowCloudflare", "true")),
            settings.getOrDefault("network.hostMode", "direct"), settings.getOrDefault("network.wireGuardEndpoint", ""),
            settings.getOrDefault("network.wireGuardEgress", ""));
    }

    Preference preference(UUID user, Policy policy) {
        return db.query("select dns,connection from network_preference where account_id=?", (rows, index) ->
            new Preference(rows.getString(1), rows.getString(2)), user).stream().findFirst()
            .filter(value -> allowed(value.dns(), policy) && policy.hostMode().equals(value.connection()))
            .orElseGet(() -> new Preference(policy.defaultDns(), policy.hostMode()));
    }

    String dnsForPlaylist(String stored) {
        var policy = policy();
        return allowed(stored, policy) ? stored : policy.defaultDns();
    }

    static boolean allowed(String dns, Policy policy) {
        return ("system".equals(dns) && policy.allowSystem()) || ("cloudflare".equals(dns) && policy.allowCloudflare());
    }

    void save(UUID user, Preference preference) {
        var policy = policy();
        if (!allowed(preference.dns(), policy) || !policy.hostMode().equals(preference.connection()))
            throw ApiErrors.bad("A opção não está disponível neste servidor.");
        db.update("""
            insert into network_preference(account_id,dns,connection) values (?,?,?)
            on conflict(account_id) do update set dns=excluded.dns,connection=excluded.connection
            """, user, preference.dns(), preference.connection());
    }

    @org.springframework.transaction.annotation.Transactional
    public void savePolicy(Policy policy) {
        if (!allowed(policy.defaultDns(), policy) || policy.hostMode() == null
            || !List.of("direct", "warp", "wireguard").contains(policy.hostMode())) throw ApiErrors.bad("Configuração de rede inválida.");
        String endpoint = policy.wireGuardEndpoint() == null ? "" : policy.wireGuardEndpoint().strip();
        String egress = policy.wireGuardEgress() == null ? "" : policy.wireGuardEgress().strip();
        var port = java.util.regex.Pattern.compile("^(?:[a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\]):([0-9]{1,5})$").matcher(endpoint);
        if (!endpoint.isEmpty() && (!port.matches() || Integer.parseInt(port.group(1)) < 1 || Integer.parseInt(port.group(1)) > 65535))
            throw ApiErrors.bad("Informe o endpoint WireGuard no formato host:porta.");
        if (!egress.isEmpty() && !HostRoute.sameAddress(egress, egress))
            throw ApiErrors.bad("Informe um IP de saída válido.");
        if ("wireguard".equals(policy.hostMode()) && (endpoint.isEmpty() || egress.isEmpty()))
            throw ApiErrors.bad("Informe o endpoint e o IP de saída esperado para verificar o WireGuard.");
        Map.of("defaultDns", policy.defaultDns(), "allowSystem", Boolean.toString(policy.allowSystem()),
            "allowCloudflare", Boolean.toString(policy.allowCloudflare()), "hostMode", policy.hostMode(),
            "wireGuardEndpoint", endpoint, "wireGuardEgress", egress).forEach((key, value) ->
            db.update("insert into platform_setting(name,value) values (?,?) on conflict(name) do update set value=excluded.value",
                "network." + key, value));
    }
}

@RestController
class NetworkApi {
    private final NetworkSettings settings;
    private final Upstream upstream;
    private final Catalog catalog;
    private final HostRoute route;

    NetworkApi(NetworkSettings settings, Upstream upstream, Catalog catalog, HostRoute route) {
        this.settings = settings;
        this.upstream = upstream;
        this.catalog = catalog;
        this.route = route;
    }

    record Options(NetworkSettings.Preference preference, boolean allowSystem, boolean allowCloudflare, String hostMode) {}
    record Probe(String target, UUID channelId) {}
    record Check(String stage, String status, long latencyMs, String detail) {}

    @GetMapping("/api/network")
    Options options(@AuthenticationPrincipal Jwt jwt) {
        var policy = settings.policy();
        return new Options(settings.preference(user(jwt), policy), policy.allowSystem(), policy.allowCloudflare(), policy.hostMode());
    }

    @PutMapping("/api/network")
    Options save(@AuthenticationPrincipal Jwt jwt, @RequestBody NetworkSettings.Preference preference) {
        settings.save(user(jwt), preference);
        return options(jwt);
    }

    @GetMapping("/api/admin/network")
    NetworkSettings.Policy policy() { return settings.policy(); }

    @PutMapping("/api/admin/network")
    NetworkSettings.Policy policy(@RequestBody NetworkSettings.Policy policy) {
        settings.savePolicy(policy);
        return settings.policy();
    }

    @PostMapping("/api/network/test")
    List<Check> test(@AuthenticationPrincipal Jwt jwt, @RequestBody Probe probe) {
        var policy = settings.policy();
        var preference = settings.preference(user(jwt), policy);
        var checks = new java.util.ArrayList<Check>();
        var hostCheck = check("Conectividade do servidor", () -> route.inspect(policy));
        checks.add(hostCheck);
        if (!"ok".equals(hostCheck.status()) && !"direct".equals(policy.hostMode())) return checks;

        var channel = probe.channelId() == null ? null : catalog.target(user(jwt), probe.channelId());
        HttpUrl target = channel != null ? channel.url() : probe.target() == null || probe.target().isBlank() ? null : upstream.url(probe.target());
        String dns = channel == null ? preference.dns() : settings.dnsForPlaylist(channel.dns());
        checks.add(check("DNS", () -> {
            String host = target == null ? "example.com" : target.host();
            int count = upstream.lookup(host, dns).size();
            return count + " endereço(s) resolvido(s) via " + ("cloudflare".equals(dns) ? "Cloudflare DoH." : "DNS automático.");
        }));
        if (target != null) checks.add(check(channel == null ? "Servidor IPTV" : "Disponibilidade da mídia", () -> {
            try (var response = upstream.get(target, dns, channel == null ? null : "bytes=0-1023", Duration.ofSeconds(10))) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("O servidor retornou HTTP " + response.code() + ".");
                if (channel != null && response.body().byteStream().read() < 0) throw new IOException("O servidor respondeu sem dados de vídeo.");
                return "HTTP " + response.code() + (channel == null ? "." : ". A fonte retornou dados.");
            }
        }));
        return checks;
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    private Check check(String stage, Diagnostic task) {
        long start = System.nanoTime();
        try { var detail = task.run(); return new Check(stage, "ok", (System.nanoTime() - start) / 1_000_000, detail); }
        catch (Exception error) {
            String detail = error instanceof java.net.UnknownHostException ? "Não foi possível resolver o domínio."
                : error instanceof java.net.SocketTimeoutException ? "Tempo limite de conexão."
                : error instanceof IOException && error.getMessage() != null && !error.getMessage().contains("://")
                  ? error.getMessage() : "Não foi possível concluir o teste.";
            return new Check(stage, "error", (System.nanoTime() - start) / 1_000_000, detail);
        }
    }

    interface Diagnostic { String run() throws IOException; }
}


