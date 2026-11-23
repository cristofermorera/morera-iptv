package nexo;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Epg {
    private final JdbcTemplate db;
    private final Upstream upstream;
    private final SourceSecrets secrets;
    private final NetworkSettings network;
    private final HostRoute route;

    Epg(JdbcTemplate db, Upstream upstream, SourceSecrets secrets, NetworkSettings network, HostRoute route) {
        this.db = db; this.upstream = upstream; this.secrets = secrets; this.network = network; this.route = route;
    }

    @Transactional(rollbackFor = Exception.class)
    public int refresh(UUID id) throws IOException, XMLStreamException {
        var source = db.queryForList("select source from epg_source where id=?", String.class, id).stream().findFirst().orElseThrow(ApiErrors::missing);
        var url = upstream.url(secrets.open(source));
        var policy = network.policy();
        route.require(policy);
        try (var response = upstream.get(url, policy.defaultDns(), null, Duration.ofMinutes(5))) {
            if (!response.isSuccessful() || response.body() == null) throw ApiErrors.bad("O servidor EPG retornou HTTP " + response.code() + ".");
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("Entidade externa bloqueada."); });
            return parse(id, factory, response.body().byteStream());
        }
    }

    private int parse(UUID id, XMLInputFactory factory, java.io.InputStream input) throws IOException, XMLStreamException {
        var limited = new java.io.FilterInputStream(input) {
            long size;
            private void count(int amount) throws IOException {
                if (amount > 0 && (size += amount) > 128L * 1024 * 1024) throw new IOException("EPG acima do limite.");
            }
            @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
            @Override public int read(byte[] data, int from, int len) throws IOException { int amount = in.read(data, from, len); count(amount); return amount; }
        };
        var reader = factory.createXMLStreamReader(limited);
        try {
            while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) { }
            if (reader.getEventType() != XMLStreamConstants.START_ELEMENT || !"tv".equals(reader.getLocalName()))
                throw ApiErrors.bad("O arquivo EPG deve estar no formato XMLTV.");
            db.update("delete from epg_program where source_id=?", id);
            List<Object[]> batch = new ArrayList<>();
            int count = 0;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT || !"programme".equals(reader.getLocalName())) continue;
                if (++count > 500_000) throw ApiErrors.bad("EPG acima do limite de 500 mil programas.");
                String channel = Catalog.text(reader.getAttributeValue(null, "channel"), "", 512);
                Instant start = date(reader.getAttributeValue(null, "start")), end = date(reader.getAttributeValue(null, "stop"));
                String title = "";
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.END_ELEMENT && "programme".equals(reader.getLocalName())) break;
                    if (event == XMLStreamConstants.START_ELEMENT && title.isBlank() && "title".equals(reader.getLocalName()))
                        title = Catalog.text(reader.getElementText(), "", 512);
                }
                if (!channel.isBlank() && !title.isBlank() && start != null && end != null && end.isAfter(start)) {
                    batch.add(new Object[]{id, channel, Timestamp.from(start), Timestamp.from(end), title});
                    if (batch.size() >= 500) { insert(batch); batch.clear(); }
                }
            }
            insert(batch);
            db.update("update epg_source set updated_at=? where id=?", Timestamp.from(Instant.now()), id);
            return count;
        } finally { reader.close(); }
    }

    private void insert(List<Object[]> rows) {
        if (!rows.isEmpty()) db.batchUpdate("insert into epg_program values (?,?,?,?,?) on conflict do nothing", rows);
    }

    private Instant date(String value) {
        if (value == null) return null;
        try { return ZonedDateTime.parse(value.strip(), DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")).toInstant(); }
        catch (java.time.DateTimeException error) { return null; }
    }
}

