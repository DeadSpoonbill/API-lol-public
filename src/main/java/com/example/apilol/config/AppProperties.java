package com.example.apilol.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Getter
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Riot riot = new Riot();
    private Target target = new Target();

    public static class Riot {
        private String apiKey;
        private String router = "europe";
        private String type;
        private List<Integer> queues;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getRouter() { return router; }
        public void setRouter(String router) { this.router = router; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<Integer> getQueues() { return queues; }
        public void setQueues(List<Integer> queues) { this.queues = queues; }
    }
    public static class Target {
        private String gameName;
        private String tagLine;
        private int count = 30;
        public String getGameName() { return gameName; }
        public void setGameName(String gameName) { this.gameName = gameName; }
        public String getTagLine() { return tagLine; }
        public void setTagLine(String tagLine) { this.tagLine = tagLine; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }
}