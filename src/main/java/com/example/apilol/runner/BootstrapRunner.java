package com.example.apilol.runner;

import com.example.apilol.config.AppProperties;
import com.example.apilol.service.IngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapRunner implements CommandLineRunner {
    private final AppProperties props;
    private final IngestionService service;
    public BootstrapRunner(AppProperties props, IngestionService service) {
        this.props = props; this.service = service;
    }
    @Override
    public void run(String... args) throws Exception {
        String name = props.getTarget().getGameName();
        String tag  = props.getTarget().getTagLine();
        int count   = props.getTarget().getCount();
        if (name == null || name.isBlank() || tag == null || tag.isBlank()) {
            System.out.println("⚠️  GAME_NAME / TAG_LINE manquants → pas d’ingestion auto.");
            return;
        }
        System.out.printf("→ Ingestion %s#%s (%d matchs)…%n", name, tag, count);
        service.ingestPlayer(name, tag, count);
        System.out.println("✅ Terminé.");
    }
}