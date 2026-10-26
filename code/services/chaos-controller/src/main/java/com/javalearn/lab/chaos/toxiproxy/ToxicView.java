package com.javalearn.lab.chaos.toxiproxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Console-facing view of one toxic as reported by Toxiproxy.
 *
 * <p>{@code attributes} is kept generic because Toxiproxy serialises numbers as
 * JSON numbers and may add fields in a newer release; the client does not need
 * to model every toxic type it never creates.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToxicView(String name, String type, Map<String, Object> attributes, double toxicity) {

    public ToxicView {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
