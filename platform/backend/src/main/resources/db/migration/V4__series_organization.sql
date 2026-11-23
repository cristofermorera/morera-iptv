CREATE FUNCTION iptv_episode_parts(value text) RETURNS text[]
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT regexp_match(value,
        '^(.*?)[[:space:][:punct:]]+(?:(?:s|t(?:emporada)?)\s*([0-9]{1,4})\s*[-._ ]*e(?:p(?:is[oó]dio)?)?\s*\.?\s*([0-9]{1,4})|([0-9]{1,4})\s*x\s*([0-9]{1,4}))([^0-9]|$)', 'i')
$$;

ALTER TABLE channel
  ADD COLUMN series_key text GENERATED ALWAYS AS (
    CASE WHEN kind = 'series' THEN lower(coalesce(nullif(btrim((iptv_episode_parts(name))[1], ' -._'), ''), name)) END
  ) STORED,
  ADD COLUMN season_number integer GENERATED ALWAYS AS (
    CASE WHEN kind = 'series' THEN coalesce((iptv_episode_parts(name))[2], (iptv_episode_parts(name))[4])::integer END
  ) STORED,
  ADD COLUMN episode_number integer GENERATED ALWAYS AS (
    CASE WHEN kind = 'series' THEN coalesce((iptv_episode_parts(name))[3], (iptv_episode_parts(name))[5])::integer END
  ) STORED;

CREATE INDEX channel_series_lookup ON channel(playlist_id, series_key, season_number, episode_number)
    WHERE kind = 'series';
