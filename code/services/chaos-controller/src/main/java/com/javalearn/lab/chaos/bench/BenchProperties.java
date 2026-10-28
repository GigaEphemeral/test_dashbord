package com.javalearn.lab.chaos.bench;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stage2 B2-01b bench scenario catalog configuration. The manifest is the same
 * single source of truth the shell runners read; the controller only projects
 * it read-only over HTTP.
 *
 * <p>The controller image deliberately does not COPY {@code code/lab/**}, so
 * compose mounts {@code code/lab/k6} at {@code /app/k6} and the default below is
 * a {@code file:} location. Any Spring resource prefix is accepted
 * ({@code classpath:} works for a packaged copy), but a {@code classpath:}
 * manifest cannot stat the sibling scripts and therefore reports
 * {@code scriptExists=false}.</p>
 */
@Component
@ConfigurationProperties(prefix = "lab.bench")
public class BenchProperties {

    /**
     * Manifest resource. Default mirrors application.yml, compose.base.yml and
     * .env.example; a blank value is refused per request (503/50303), never
     * silently defaulted.
     */
    private String scenariosLocation = "file:/app/k6/scenarios/manifest.json";

    public String getScenariosLocation() {
        return scenariosLocation;
    }

    public void setScenariosLocation(String scenariosLocation) {
        this.scenariosLocation = scenariosLocation;
    }
}
