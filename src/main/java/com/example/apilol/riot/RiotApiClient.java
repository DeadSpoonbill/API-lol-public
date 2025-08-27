package com.example.apilol.riot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.apilol.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class RiotApiClient {
    private final WebClient client;
    private final ObjectMapper om = new ObjectMapper();
    private final AppProperties props;

    public RiotApiClient(AppProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .baseUrl("https://" + props.getRiot().getRouter() + ".api.riotgames.com")
                .defaultHeader("X-Riot-Token", props.getRiot().getApiKey())
                .build();
    }

    private String getBody(String path, Map<String, String> query) throws Exception {
        for (;;) {
            ClientResponse resp = client.get()
                    .uri(uri -> {
                        var b = uri.path(path);
                        if (query != null) query.forEach(b::queryParam);
                        return b.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange()
                    .block(Duration.ofSeconds(30));

            if (resp == null) throw new RuntimeException("No response");
            HttpStatus st = (HttpStatus) resp.statusCode();
            if (st.value() == 404) return null;
            if (st.value() == 429) {
                String ra = resp.headers().asHttpHeaders().getFirst("Retry-After");
                long sleep = (ra != null ? Long.parseLong(ra) : 2);
                Thread.sleep(1000L * sleep);
                continue;
            }
            if (st.is5xxServerError()) { Thread.sleep(2000); continue; }
            if (!st.is2xxSuccessful()) {
                String body = resp.bodyToMono(String.class).block();
                throw new RuntimeException("HTTP " + st.value() + " : " + body);
            }
            return resp.bodyToMono(String.class).block();
        }
    }

    public Map<String, Object> getAccountByRiotId(String gameName, String tagLine) throws Exception {
        String name = URLEncoder.encode(gameName, StandardCharsets.UTF_8);
        String tag  = URLEncoder.encode(tagLine, StandardCharsets.UTF_8);
        String body = getBody("/riot/account/v1/accounts/by-riot-id/" + name + "/" + tag, Map.of());
        if (body == null) return null;
        return om.readValue(body, new TypeReference<>() {});
    }

    public List<String> getMatchIds(String puuid, int count, Integer queue, String type) throws Exception {
        Map<String,String> q = new LinkedHashMap<>();
        q.put("start", "0");
        q.put("count", String.valueOf(count));
        if (queue != null) q.put("queue", String.valueOf(queue));
        if (type != null && !type.isBlank()) q.put("type", type);
        String body = getBody("/lol/match/v5/matches/by-puuid/" + puuid + "/ids", q);
        if (body == null) return List.of();
        return om.readValue(body, new TypeReference<>() {
        });
    }

    public Map<String,Object> getMatch(String matchId) throws Exception {
        String body = getBody("/lol/match/v5/matches/" + matchId, Map.of());
        if (body == null) return null;
        return om.readValue(body, new TypeReference<>() {});
    }

    public Map<String,Object> getTimeline(String matchId) throws Exception {
        String body = getBody("/lol/match/v5/matches/" + matchId + "/timeline", Map.of());
        if (body == null) return null;
        return om.readValue(body, new TypeReference<>() {});
    }
}