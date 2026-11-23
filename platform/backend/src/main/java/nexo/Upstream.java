package nexo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class Upstream {
    private final OkHttpClient plain;
    private final OkHttpClient doh;
    private final OkHttpClient resolver;
    private final ObjectMapper json;
    private final boolean allowPrivate;

    Upstream(ObjectMapper json, @Value("${nexo.allow-private-sources}") boolean allowPrivate) {
        this.json = json;
        this.allowPrivate = allowPrivate;
        resolver = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false).retryOnConnectionFailure(true).build();
        plain = new OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .dns(name -> addresses(Dns.SYSTEM.lookup(name), name))
            .followRedirects(false).readTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
        doh = plain.newBuilder().dns(name -> addresses(resolve(name), name)).build();
    }

    HttpUrl url(String value) {
        var url = value == null ? null : HttpUrl.parse(value);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty())
            throw ApiErrors.bad("Informe um endereço HTTP ou HTTPS válido.");
        return url;
    }

    Response get(HttpUrl url, String dns, String range, Duration timeout) throws IOException {
        var client = "cloudflare".equals(dns) ? doh : plain;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!allowPrivate && literal(url.host()) && !publicAddress(InetAddress.getByName(url.host())))
                throw ApiErrors.bad("O endereço da fonte não é público.");
            var request = new Request.Builder().url(url).header("User-Agent", "Nexo-IPTV");
            if (range != null) request.header("Range", range);
            var call = client.newCall(request.build());
            call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var response = call.execute();
            if (!response.isRedirect()) return response;
            String target = response.header("Location");
            response.close();
            if (target == null || (url = url.resolve(target)) == null) throw new IOException("Redirecionamento inválido.");
            url = url(url.toString());
        }
        throw new IOException("Muitos redirecionamentos.");
    }

    List<InetAddress> lookup(String hostname, String dns) throws IOException {
        if (literal(hostname)) return addresses(List.of(InetAddress.getByName(hostname)), hostname);
        return addresses("cloudflare".equals(dns) ? resolve(hostname) : Dns.SYSTEM.lookup(hostname), hostname);
    }

    Map<String, String> trace() throws IOException {
        try (var response = resolver.newCall(new Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Consulta de rota indisponível.");
            var values = new HashMap<String, String>();
            for (var line : response.body().string().split("\n")) {
                var pair = line.split("=", 2);
                if (pair.length == 2) values.put(pair[0], pair[1].strip());
            }
            return values;
        }
    }

    private List<InetAddress> resolve(String host) throws UnknownHostException {
        if (literal(host)) return Dns.SYSTEM.lookup(host);
        var addresses = new ArrayList<InetAddress>();
        for (String type : List.of("A", "AAAA")) {
            for (String resolverIp : List.of("1.1.1.1", "1.0.0.1")) {
                try {
                    var url = new HttpUrl.Builder().scheme("https").host(resolverIp).addPathSegment("dns-query")
                        .addQueryParameter("name", host).addQueryParameter("type", type).build();
                    var request = new Request.Builder().url(url).header("Accept", "application/dns-json").build();
                    try (var response = resolver.newCall(request).execute()) {
                        if (!response.isSuccessful() || response.body() == null) continue;
                        JsonNode result = json.readTree(response.body().byteStream());
                        if (result.path("Status").asInt(-1) == 3) throw new UnknownHostException(host);
                        if (result.path("Status").asInt(-1) != 0) continue;
                        for (var record : result.path("Answer")) {
                            String address = record.path("data").asText();
                            if (record.path("type").asInt() == ("A".equals(type) ? 1 : 28) && literal(address))
                                addresses.add(InetAddress.getByName(address));
                        }
                        break;
                    }
                } catch (UnknownHostException error) { throw error; }
                catch (IOException ignored) { /* Try the secondary Cloudflare resolver. */ }
            }
        }
        if (addresses.isEmpty()) throw new UnknownHostException(host);
        return addresses;
    }

    private List<InetAddress> addresses(List<InetAddress> values, String host) throws UnknownHostException {
        var filtered = allowPrivate ? values : values.stream().filter(Upstream::publicAddress).toList();
        if (filtered.isEmpty()) throw new UnknownHostException(host);
        return filtered;
    }

    private static boolean literal(String value) {
        return value.contains(":") || value.matches("[0-9.]+");
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 16) return (a & 0xe0) == 0x20;
        return a > 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127) && !(a == 198 && (b == 18 || b == 19));
    }
}

