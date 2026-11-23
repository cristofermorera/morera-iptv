package nexo;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class HostRoute {
    private final Upstream upstream;
    private String lastMode = "";
    private String lastEgress = "";
    private String failure;
    private long checkedAt;

    HostRoute(Upstream upstream) { this.upstream = upstream; }

    synchronized void require(NetworkSettings.Policy policy) {
        if ("direct".equals(policy.hostMode())) return;
        long now = System.nanoTime();
        long ttl = failure == null ? 15_000_000_000L : 5_000_000_000L;
        if (!policy.hostMode().equals(lastMode) || !policy.wireGuardEgress().equals(lastEgress) || now - checkedAt >= ttl) {
            try { inspect(policy); failure = null; }
            catch (IOException error) { failure = error.getMessage(); }
            lastMode = policy.hostMode();
            lastEgress = policy.wireGuardEgress();
            checkedAt = System.nanoTime();
        }
        if (failure != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failure);
    }

    String inspect(NetworkSettings.Policy policy) throws IOException {
        java.util.Map<String, String> trace;
        try { trace = upstream.trace(); }
        catch (IOException error) { throw new IOException("Não foi possível verificar a rota de saída do servidor."); }
        boolean warp = List.of("on", "plus").contains(trace.getOrDefault("warp", ""));
        switch (policy.hostMode()) {
            case "warp":
                if (!warp) throw new IOException("WARP não foi detectado na rota de saída do host.");
                return "WARP detectado na rota de teste.";
            case "wireguard":
                if (warp || !sameAddress(policy.wireGuardEgress(), trace.get("ip")))
                    throw new IOException("O IP de saída não confere ou o WARP está ativo.");
                return "IP de saída confere. Verifique o túnel no host.";
            default:
                if (warp) throw new IOException("O host está saindo pelo WARP.");
                return "Rota do host acessível. O navegador não controla o túnel.";
        }
    }

    static boolean sameAddress(String first, String second) {
        if (first == null || second == null || !first.matches("[0-9a-fA-F:.]+") || !second.matches("[0-9a-fA-F:.]+")
            || !(first.contains(".") || first.contains(":")) || !(second.contains(".") || second.contains(":"))) return false;
        try { return java.net.InetAddress.ofLiteral(first).equals(java.net.InetAddress.ofLiteral(second)); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
