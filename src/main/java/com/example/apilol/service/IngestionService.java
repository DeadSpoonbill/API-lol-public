package com.example.apilol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.apilol.config.AppProperties;
import com.example.apilol.db.Db;
import com.example.apilol.riot.RiotApiClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

import static com.example.apilol.db.Db.jsonb;

@Service
public class IngestionService {
    private final RiotApiClient api;
    private final JdbcTemplate jdbc;
    private final AppProperties cfg;
    private final ObjectMapper om = new ObjectMapper();

    public IngestionService(RiotApiClient api, Db db, AppProperties cfg) {
        this.api = api;
        this.jdbc = db.jdbc();
        this.cfg = cfg;
    }

    /** Propage les exceptions car RiotApiClient déclare throws Exception */
    public void ingestPlayer(String gameName, String tagLine, int count) throws Exception {
        var account = api.getAccountByRiotId(gameName, tagLine);
        if (account == null) throw new RuntimeException("PUUID introuvable");
        String puuid = Objects.toString(account.get("puuid"), null);
        upsertSummoner(puuid, account);

        List<Integer> queues = cfg.getRiot().getQueues();
        String type = cfg.getRiot().getType();
        List<String> matchIds = new ArrayList<>();

        if (queues != null && !queues.isEmpty()) {
            int per = Math.max(1, (int) Math.ceil(count / (double) queues.size()));
            for (Integer q : queues) matchIds.addAll(api.getMatchIds(puuid, per, q, type));
            matchIds = new ArrayList<>(new LinkedHashSet<>(matchIds)); // dédup
        } else {
            matchIds = api.getMatchIds(puuid, count, null, type);
        }

        for (String mid : matchIds) {
            var match = api.getMatch(mid);
            if (match == null) continue;
            upsertMatch(match);

            var timeline = api.getTimeline(mid);
            insertTimeline(mid, timeline);
        }
    }

    private void upsertSummoner(String puuid, Map<String,Object> accountRaw) throws Exception {
        String raw = om.writeValueAsString(Map.of("account", accountRaw));
        jdbc.update("""
            INSERT INTO lol.summoner(puuid, game_name, tag_line, last_seen_at, raw)
            VALUES (?, ?, ?, NOW(), ?)
            ON CONFLICT (puuid) DO UPDATE SET
              game_name = EXCLUDED.game_name,
              tag_line  = EXCLUDED.tag_line,
              last_seen_at = NOW(),
              raw = COALESCE(lol.summoner.raw, '{}'::jsonb) || EXCLUDED.raw
        """,
                puuid,
                Objects.toString(accountRaw.get("gameName"), null),
                Objects.toString(accountRaw.get("tagLine"), null),
                jsonb(raw)
        );
    }

    @SuppressWarnings("unchecked")
    private void upsertMatch(Map<String,Object> match) throws Exception {
        var meta = (Map<String,Object>) match.get("metadata");
        var info = (Map<String,Object>) match.get("info");
        String mid = Objects.toString(meta.get("matchId"), null);
        String gameVersion = Objects.toString(info.get("gameVersion"), null);
        String patch = (gameVersion != null && gameVersion.contains("."))
                ? gameVersion.split("\\.")[0] + "." + gameVersion.split("\\.")[1] : null;

        jdbc.update("""
            INSERT INTO lol.match(match_id, data_version, game_version, patch, queue_id,
              game_creation_ms, game_start_ms, game_end_ms, game_duration_s, map_id, platform_id,
              tournament_code, region_router, raw)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (match_id) DO UPDATE SET raw = EXCLUDED.raw
        """,
                mid,
                Objects.toString(meta.get("dataVersion"), null),
                gameVersion, patch,
                toInt(info.get("queueId")),
                toLong(info.get("gameCreation")),
                toLong(info.get("gameStartTimestamp")),
                toLong(info.get("gameEndTimestamp")),
                toInt(info.get("gameDuration")),
                toInt(info.get("mapId")),
                Objects.toString(info.get("platformId"), null),
                Objects.toString(info.get("tournamentCode"), null),
                cfg.getRiot().getRouter(),
                jsonb(om.writeValueAsString(match))
        );

        // teams
        var teams = (List<Map<String,Object>>) info.getOrDefault("teams", List.of());
        for (var t : teams) {
            Integer teamId = toInt(t.get("teamId"));
            var objectives = (Map<String,Object>) t.getOrDefault("objectives", Map.of());
            var baron = (Map<String,Object>) objectives.getOrDefault("baron", Map.of());
            var dragon = (Map<String,Object>) objectives.getOrDefault("dragon", Map.of());
            var herald = (Map<String,Object>) objectives.getOrDefault("riftHerald", Map.of());
            var inhib  = (Map<String,Object>) objectives.getOrDefault("inhibitor", Map.of());
            var tower  = (Map<String,Object>) objectives.getOrDefault("tower", Map.of());
            var bans = (List<Map<String,Object>>) t.getOrDefault("bans", List.of());

            Integer[] b = new Integer[5];
            for (int i=0;i<5;i++) b[i] = (i < bans.size()) ? toInt(bans.get(i).get("championId")) : null;

            jdbc.update("""
                INSERT INTO lol.team(match_id, team_id, win, baron_kills, dragon_kills, rift_herald_kills,
                  inhibitor_kills, tower_kills, ban0, ban1, ban2, ban3, ban4)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (match_id, team_id) DO UPDATE SET win = EXCLUDED.win
            """,
                    mid, teamId, toBool(t.get("win")),
                    toInt(baron.get("kills")), toInt(dragon.get("kills")), toInt(herald.get("kills")),
                    toInt(inhib.get("kills")), toInt(tower.get("kills")),
                    b[0], b[1], b[2], b[3], b[4]
            );
        }

        // participants
        var parts = (List<Map<String,Object>>) info.getOrDefault("participants", List.of());
        for (var p : parts) {
            jdbc.update("""
                INSERT INTO lol.participant(
                  match_id, participant_id, puuid, team_id, champion_id, champion_name,
                  riot_id_game_name, riot_id_tagline, individual_position, lane, role,
                  summoner1_id, summoner2_id, item0, item1, item2, item3, item4, item5, item6,
                  kills, deaths, assists, total_damage_to_champs, total_damage_taken,
                  damage_self_mitigated, gold_earned, vision_score, wards_placed, wards_killed,
                  detector_wards_placed, champ_level, total_minions_killed, neutral_minions_killed,
                  time_ccing_others, win, perks, stats
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (match_id, participant_id) DO UPDATE SET perks = EXCLUDED.perks, stats = EXCLUDED.stats
            """,
                    mid, toInt(p.get("participantId")), Objects.toString(p.get("puuid"), null),
                    toInt(p.get("teamId")), toInt(p.get("championId")),
                    Objects.toString(p.get("championName"), null),
                    Objects.toString(p.get("riotIdGameName"), null), Objects.toString(p.get("riotIdTagline"), null),
                    Objects.toString(p.get("individualPosition"), null),
                    Objects.toString(p.get("lane"), null), Objects.toString(p.get("role"), null),
                    toInt(p.get("summoner1Id")), toInt(p.get("summoner2Id")),
                    toInt(p.get("item0")), toInt(p.get("item1")), toInt(p.get("item2")),
                    toInt(p.get("item3")), toInt(p.get("item4")), toInt(p.get("item5")), toInt(p.get("item6")),
                    toInt(p.get("kills")), toInt(p.get("deaths")), toInt(p.get("assists")),
                    toInt(p.get("totalDamageDealtToChampions")), toInt(p.get("totalDamageTaken")),
                    toInt(p.get("damageSelfMitigated")), toInt(p.get("goldEarned")),
                    toDouble(p.get("visionScore")), toInt(p.get("wardsPlaced")), toInt(p.get("wardsKilled")),
                    toInt(p.get("detectorWardsPlaced")), toInt(p.get("champLevel")),
                    toInt(p.get("totalMinionsKilled")), toInt(p.get("neutralMinionsKilled")),
                    toInt(p.get("timeCCingOthers")), toBool(p.get("win")),
                    jsonb(om.writeValueAsString(p.get("perks"))),
                    jsonb(om.writeValueAsString(stripParticipantKnown(p)))
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void insertTimeline(String matchId, Map<String,Object> timeline) throws Exception {
        if (timeline == null) return;
        var info = (Map<String,Object>) timeline.get("info");
        var frames = (List<Map<String,Object>>) info.getOrDefault("frames", List.of());

        for (int idx=0; idx<frames.size(); idx++) {
            var fr = frames.get(idx);
            Long ts = toLong(fr.get("timestamp"));
            var pf = (Map<String,Object>) fr.getOrDefault("participantFrames", Map.of());

            for (var e : pf.entrySet()) {
                int pid = Integer.parseInt(e.getKey());
                var d = (Map<String,Object>) e.getValue();
                var pos = (Map<String,Object>) d.getOrDefault("position", Map.of());
                jdbc.update("""
                    INSERT INTO lol.participant_frame(
                      match_id, frame_index, ts_ms, participant_id, total_gold, current_gold,
                      xp, level, minions_killed, jungle_minions_killed, position_x, position_y, damage_stats
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (match_id, frame_index, participant_id) DO NOTHING
                """,
                        matchId, idx, ts, pid,
                        toInt(d.get("totalGold")), toInt(d.get("currentGold")),
                        toInt(d.get("xp")), toInt(d.get("level")),
                        toInt(d.get("minionsKilled")), toInt(d.get("jungleMinionsKilled")),
                        toInt(pos.get("x")), toInt(pos.get("y")),
                        jsonb(om.writeValueAsString(d.get("damageStats")))
                );
            }

            var events = (List<Map<String,Object>>) fr.getOrDefault("events", List.of());
            for (var ev : events) {
                var pos = (Map<String,Object>) ev.getOrDefault("position", Map.of());
                List<Integer> assists = (List<Integer>) ev.get("assistingParticipantIds");

                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO lol.timeline_event(
                          match_id, ts_ms, event_type, participant_id, killer_id, victim_id,
                          team_id, assisting_ids, position_x, position_y, item_id, after_id, before_id,
                          skill_slot, level_up_type, ward_type, building_type, tower_type,
                          monster_type, monster_sub_type, bounty, gold_gain, other
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """);
                    ps.setString(1, matchId);
                    setLong(ps, 2, toLong(ev.get("timestamp")));
                    ps.setString(3, Objects.toString(ev.get("type"), null));
                    setInt(ps, 4, toInt(ev.get("participantId")));
                    setInt(ps, 5, toInt(ev.get("killerId")));
                    setInt(ps, 6, toInt(ev.get("victimId")));
                    setInt(ps, 7, toInt(ev.get("teamId")));

                    if (assists != null && !assists.isEmpty()) {
                        ps.setArray(8, con.createArrayOf("int4", assists.toArray()));
                    } else {
                        ps.setNull(8, Types.ARRAY);
                    }

                    setInt(ps, 9, toInt(pos.get("x")));
                    setInt(ps,10, toInt(pos.get("y")));
                    setInt(ps,11, toInt(ev.get("itemId")));
                    setInt(ps,12, toInt(ev.get("afterId")));
                    setInt(ps,13, toInt(ev.get("beforeId")));
                    setInt(ps,14, toInt(ev.get("skillSlot")));
                    ps.setString(15, Objects.toString(ev.get("levelUpType"), null));
                    ps.setString(16, Objects.toString(ev.get("wardType"), null));
                    ps.setString(17, Objects.toString(ev.get("buildingType"), null));
                    ps.setString(18, Objects.toString(ev.get("towerType"), null));
                    ps.setString(19, Objects.toString(ev.get("monsterType"), null));
                    ps.setString(20, Objects.toString(ev.get("monsterSubType"), null));
                    setInt(ps,21, toInt(ev.get("bounty")));
                    setInt(ps,22, toInt(ev.get("goldGain")));
                    try {
                        ps.setObject(23, jsonb(om.writeValueAsString(stripEventKnown(ev))));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new RuntimeException("Erreur sérialisation JSON (event)", e);
                    }
                    return ps;
                });
            }
        }
    }

    // utils conversions
    private static Integer toInt(Object o){ return o==null?null:(o instanceof Number n? n.intValue(): Integer.parseInt(o.toString())); }
    private static Long toLong(Object o){ return o==null?null:(o instanceof Number n? n.longValue(): Long.parseLong(o.toString())); }
    private static Double toDouble(Object o){ return o==null?null:(o instanceof Number n? n.doubleValue(): Double.parseDouble(o.toString())); }
    private static Boolean toBool(Object o){ return o==null?null:(o instanceof Boolean b? b: Boolean.parseBoolean(o.toString())); }

    // IMPORTANT: SQLException uniquement (compatible PreparedStatementCreator)
    private static void setInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }
    private static void setLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, v);
    }

    private static Map<String,Object> stripParticipantKnown(Map<String,Object> p) {
        Set<String> known = Set.of("participantId","puuid","teamId","championId","championName",
                "riotIdGameName","riotIdTagline","individualPosition","lane","role",
                "summoner1Id","summoner2Id","item0","item1","item2","item3","item4","item5","item6",
                "kills","deaths","assists","totalDamageDealtToChampions","totalDamageTaken",
                "damageSelfMitigated","goldEarned","visionScore","wardsPlaced","wardsKilled",
                "detectorWardsPlaced","champLevel","totalMinionsKilled","neutralMinionsKilled",
                "timeCCingOthers","win","perks");
        Map<String,Object> copy = new HashMap<>();
        for (var e : p.entrySet()) if (!known.contains(e.getKey())) copy.put(e.getKey(), e.getValue());
        return copy;
    }

    private static Map<String,Object> stripEventKnown(Map<String,Object> ev) {
        Set<String> known = Set.of("timestamp","type","participantId","killerId","victimId","teamId",
                "assistingParticipantIds","position","itemId","afterId","beforeId","skillSlot",
                "levelUpType","wardType","buildingType","towerType","monsterType","monsterSubType",
                "bounty","goldGain");
        Map<String,Object> copy = new HashMap<>();
        for (var e : ev.entrySet()) if (!known.contains(e.getKey())) copy.put(e.getKey(), e.getValue());
        return copy;
    }
}
