-- schema_lol.sql
-- Schéma LoL compatible Match-V5 + Timeline-V5 + Account-V1
-- Neon: SSL requis côté client; aucune extension exotique.

CREATE SCHEMA IF NOT EXISTS lol;

-- UUID sans uuid-ossp: on utilise pgcrypto (gen_random_uuid)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1) Journal d’ingestion (utile pour debug/rate limit)
CREATE TABLE IF NOT EXISTS lol.ingestion_log (
                                                 id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source             text NOT NULL,           -- 'riot'
    method             text NOT NULL,           -- 'GET'
    url                text NOT NULL,
    http_status        int,
    retry_after_seconds int,
    inserted_at        timestamptz DEFAULT now(),
    note               text
    );

-- 2) Compte / joueur
CREATE TABLE IF NOT EXISTS lol.summoner (
                                            puuid              text PRIMARY KEY,
                                            game_name          text,         -- Riot ID (gameName)
                                            tag_line           text,         -- Riot ID (tagLine)
                                            summoner_id        text,         -- (Summoner-V4) encore utile dans certains cas
                                            account_id         text,         -- legacy
                                            profile_icon_id    int,
                                            revision_date_ms   bigint,
                                            summoner_level     int,
                                            last_seen_at       timestamptz,
                                            raw                jsonb          -- payload brut de /summoner-v4 ou account-v1
);

CREATE INDEX IF NOT EXISTS idx_summoner_game_tag
    ON lol.summoner (lower(game_name), lower(tag_line));

-- 3) Match (info globales)
CREATE TABLE IF NOT EXISTS lol.match (
                                         match_id             text PRIMARY KEY,     -- e.g. "EUW1_6801234567"
                                         data_version         text,
                                         game_version         text,                 -- e.g. "14.16.XXXX"
                                         patch                text,                 -- "14.16" (dérivé)
                                         queue_id             int,
                                         game_creation_ms     bigint,
                                         game_start_ms        bigint,
                                         game_end_ms          bigint,
                                         game_duration_s      int,
                                         map_id               int,
                                         platform_id          text,                 -- e.g. "EUW1"
                                         tournament_code      text,
                                         region_router        text,                 -- 'europe'/'americas'/'asia'
                                         raw                  jsonb                 -- payload brut de /lol/match/v5/matches/{id}
);

CREATE INDEX IF NOT EXISTS idx_match_queue_patch
    ON lol.match (queue_id, patch);

-- 4) Teams (objectifs par équipe)
CREATE TABLE IF NOT EXISTS lol.team (
                                        match_id          text REFERENCES lol.match(match_id) ON DELETE CASCADE,
    team_id           int CHECK (team_id IN (100,200)),
    win               boolean,
    baron_kills       int,
    dragon_kills      int,
    rift_herald_kills int,
    inhibitor_kills   int,
    tower_kills       int,
    ban0 int, ban1 int, ban2 int, ban3 int, ban4 int,
    PRIMARY KEY (match_id, team_id)
    );

-- 5) Participants (stats fin de partie)
CREATE TABLE IF NOT EXISTS lol.participant (
                                               match_id                  text REFERENCES lol.match(match_id) ON DELETE CASCADE,
    participant_id            int CHECK (participant_id BETWEEN 1 AND 10),
    puuid                     text REFERENCES lol.summoner(puuid),
    team_id                   int,
    champion_id               int,
    champion_name             text,
    riot_id_game_name         text,
    riot_id_tagline           text,
    individual_position       text,     -- e.g. "TOP/JUNGLE/MIDDLE/BOTTOM/UTILITY"
    lane                      text,
    role                      text,
    summoner1_id              int,
    summoner2_id              int,
    item0 int, item1 int, item2 int, item3 int, item4 int, item5 int, item6 int,
    kills                     int,
    deaths                    int,
    assists                   int,
    total_damage_to_champs    int,
    total_damage_taken        int,
    damage_self_mitigated     int,
    gold_earned               int,
    vision_score              real,
    wards_placed              int,
    wards_killed              int,
    detector_wards_placed     int,
    champ_level               int,
    total_minions_killed      int,
    neutral_minions_killed    int,
    time_ccing_others         int,
    win                       boolean,
    perks                     jsonb,  -- runes/arbre
    stats                     jsonb,  -- stats brutes restantes
    PRIMARY KEY (match_id, participant_id)
    );

CREATE INDEX IF NOT EXISTS idx_participant_puuid ON lol.participant (puuid);
CREATE INDEX IF NOT EXISTS idx_participant_team ON lol.participant (match_id, team_id);

-- 6) Timeline - frames (par minute/intervalle)
CREATE TABLE IF NOT EXISTS lol.participant_frame (
                                                     match_id              text NOT NULL,
                                                     frame_index           int  NOT NULL,    -- 0..N (ordre des frames)
                                                     ts_ms                 bigint NOT NULL,  -- timestamp absolu depuis début de la game
                                                     participant_id        int NOT NULL,
                                                     total_gold            int,
                                                     current_gold          int,
                                                     xp                    int,
                                                     level                 int,
                                                     minions_killed        int,
                                                     jungle_minions_killed int,
                                                     position_x            int,
                                                     position_y            int,
                                                     damage_stats          jsonb,
                                                     PRIMARY KEY (match_id, frame_index, participant_id),
    FOREIGN KEY (match_id, participant_id)
    REFERENCES lol.participant(match_id, participant_id) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_frame_pos ON lol.participant_frame (position_x, position_y);

-- 7) Timeline - events (kills/objectifs/wards/items/etc.)
CREATE TABLE IF NOT EXISTS lol.timeline_event (
                                                  event_id         bigserial PRIMARY KEY,
                                                  match_id         text NOT NULL REFERENCES lol.match(match_id) ON DELETE CASCADE,
    ts_ms            bigint NOT NULL,
    event_type       text NOT NULL,     -- e.g. 'WARD_PLACED', 'CHAMPION_KILL', ...
    participant_id   int,
    killer_id        int,
    victim_id        int,
    team_id          int,
    assisting_ids    int[],
    position_x       int,
    position_y       int,
    item_id          int,
    after_id         int,
    before_id        int,
    skill_slot       int,
    level_up_type    text,
    ward_type        text,              -- e.g. 'CONTROL_WARD', 'YELLOW_TRINKET'
    building_type    text,
    tower_type       text,
    monster_type     text,
    monster_sub_type text,
    bounty           int,
    gold_gain        int,
    other            jsonb              -- reste des champs (sûreté future)
    );

CREATE INDEX IF NOT EXISTS idx_event_type ON lol.timeline_event (event_type);
CREATE INDEX IF NOT EXISTS idx_event_ward ON lol.timeline_event (event_type, ward_type);
CREATE INDEX IF NOT EXISTS idx_event_pos  ON lol.timeline_event (position_x, position_y);

-- 8) Vues pratiques pour l’analyse initiale
-- 8.1. Placements de wards (pour heatmaps)
CREATE OR REPLACE VIEW lol.v_ward_placements AS
SELECT
    e.match_id,
    p.puuid,
    p.team_id,
    e.ts_ms,
    e.ward_type,
    e.position_x AS x,
    e.position_y AS y
FROM lol.timeline_event e
         LEFT JOIN lol.participant p
                   ON p.match_id = e.match_id AND p.participant_id = e.participant_id
WHERE e.event_type = 'WARD_PLACED';

-- 8.2. Gold/time par joueur
CREATE OR REPLACE VIEW lol.v_gold_timeseries AS
SELECT
    pf.match_id,
    pa.puuid,
    pf.frame_index,
    pf.ts_ms,
    pf.total_gold
FROM lol.participant_frame pf
         JOIN lol.participant pa
              ON pa.match_id = pf.match_id AND pa.participant_id = pf.participant_id;

-- 8.3. Kills/Deaths/Assists cumulables par match
CREATE OR REPLACE VIEW lol.v_kda AS
SELECT match_id, puuid, kills, deaths, assists
FROM lol.participant;
