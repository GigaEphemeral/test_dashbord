package com.javalearn.lab.chaos.annotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Used when {@code chaos.annotation.enabled=false}; keeps the action path identical. */
public class NoOpChaosAnnotator implements ChaosAnnotator {

    private static final Logger log = LoggerFactory.getLogger(NoOpChaosAnnotator.class);

    @Override
    public void annotate(List<String> tags, String text) {
        log.debug("Chaos annotation disabled; tags={} text={}", tags, text);
    }
}
