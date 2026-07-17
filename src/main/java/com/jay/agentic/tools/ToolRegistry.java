package com.jay.agentic.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of tools available to a run.
 *
 * <p>Registration is explicit and the registry is immutable once built. An agent
 * cannot reach a tool that was not handed to the run, which makes "what can this
 * harness do?" a question with a definite answer rather than one that requires
 * reading every agent.
 *
 * <p>That is least privilege applied to the harness itself: a run configured with
 * only read tools is incapable of writing, not merely disinclined to. The
 * capability is absent rather than guarded, and an absent capability cannot be
 * talked around by a cleverly worded prompt.
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;

    private ToolRegistry(Map<String, Tool> tools) {
        this.tools = tools;
    }

    public static ToolRegistry of(Tool... tools) {
        return of(List.of(tools));
    }

    public static ToolRegistry of(List<Tool> tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool t : tools) {
            if (map.put(t.name(), t) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + t.name());
            }
        }
        return new ToolRegistry(Map.copyOf(map));
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /** Tools that change something outside the harness. The set policy cares about. */
    public List<Tool> mutating() {
        return tools.values().stream().filter(Tool::isMutating).toList();
    }

    /**
     * The tool catalogue, formatted for a model prompt.
     *
     * <p>Generated from the registry rather than written into a prompt template,
     * so a tool cannot be described to the model in terms that differ from what
     * it actually does — the description a reviewer reads and the description the
     * model reads are the same string.
     */
    public String catalogue() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name())
                    .append(t.isMutating() ? " [mutating]" : " [read-only]")
                    .append(": ").append(t.description()).append('\n');
        }
        return sb.toString();
    }
}