package nexo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
class Sessions {
    static final Duration TTL = Duration.ofHours(12);
    private static final String INDEX = "nexo:sessions";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    Sessions(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    record Device(String id, String userId, String email, String agent, Instant createdAt, Instant expiresAt) {}

    Device start(Account person, String agent) throws JsonProcessingException {
        var now = Instant.now();
        var device = new Device(UUID.randomUUID().toString(), person.id.toString(), person.email,
            agent == null ? "" : agent.substring(0, Math.min(agent.length(), 250)), now, now.plus(TTL));
        redis.opsForValue().set(key(device.id()), json.writeValueAsString(device), TTL);
        redis.opsForZSet().add(INDEX, device.id(), device.expiresAt().toEpochMilli());
        return device;
    }

    boolean active(String id, String subject) {
        var device = find(id);
        return device != null && device.userId().equals(subject);
    }

    Device find(String id) {
        if (id == null) return null;
        var value = redis.opsForValue().get(key(id));
        if (value == null) return null;
        try { return json.readValue(value, Device.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    List<Device> list() {
        redis.opsForZSet().removeRangeByScore(INDEX, 0, Instant.now().toEpochMilli());
        var ids = redis.opsForZSet().reverseRange(INDEX, 0, -1);
        return ids == null ? List.of() : ids.stream().map(this::find).filter(Objects::nonNull).toList();
    }

    void revoke(String id) {
        redis.delete(key(id));
        redis.opsForZSet().remove(INDEX, id);
    }

    void revokeAll(UUID user) {
        list().stream().filter(device -> device.userId().equals(user.toString())).forEach(device -> revoke(device.id()));
    }

    private String key(String id) { return "nexo:session:" + id; }
}
