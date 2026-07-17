package com.jay.agentic.orchestration;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageResult;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.TaskState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The explicit dependency graph of SDLC stages.
 *
 * <p>Validated at construction: every dependency must name a declared stage,
 * and the graph must be acyclic. A cycle is not a runtime hazard to be caught
 * by a timeout — it is a malformed workflow, and it is better to refuse to
 * build one than to discover it half way through a run that has already spent
 * money on model calls.
 *
 * <p>The graph is immutable and holds no run state. It describes shape only;
 * where a given run currently stands lives in {@link TaskState}. Keeping the
 * two apart is what allows one graph instance to serve concurrent runs, and
 * what makes the graph unit-testable without executing anything.
 */
public final class WorkflowGraph {

    private final Map<StageId, StageNode> nodes;
    private final Map<StageId, Set<StageId>> dependents;

    private WorkflowGraph(Map<StageId, StageNode> nodes) {
        this.nodes = nodes;
        this.dependents = buildReverseEdges(nodes);
        validateDependenciesExist();
        detectCycles();
    }

    public static WorkflowGraph of(Collection<StageNode> nodes) {
        Map<StageId, StageNode> map = new EnumMap<>(StageId.class);
        for (StageNode n : nodes) {
            if (map.put(n.id(), n) != null) {
                throw new IllegalArgumentException("duplicate stage declared: " + n.id());
            }
        }
        return new WorkflowGraph(map);
    }

    // ---- structure ----

    public StageNode node(StageId id) {
        StageNode n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("no such stage in graph: " + id);
        return n;
    }

    public Set<StageId> stageIds() {
        return nodes.keySet();
    }

    public List<StageId> declaredOrder() {
        return List.copyOf(nodes.keySet());
    }

    /** All nodes in the graph. Used by the orchestrator to sweep for inapplicable stages. */
    public Collection<StageNode> nodes() {
        return nodes.values();
    }

    /** Stages that depend on the given stage — the blast radius when it changes. */
    public Set<StageId> dependentsOf(StageId id) {
        return dependents.getOrDefault(id, Set.of());
    }

    /**
     * Every stage reachable downstream of the given one, transitively.
     *
     * <p>This is what staleness propagation needs: when IMPLEMENT re-runs, it is
     * not enough to invalidate TEST — whatever consumed TEST's output is equally
     * built on sand.
     */
    public Set<StageId> transitiveDependentsOf(StageId id) {
        Set<StageId> found = new LinkedHashSet<>();
        Deque<StageId> queue = new ArrayDeque<>(dependentsOf(id));
        while (!queue.isEmpty()) {
            StageId next = queue.poll();
            if (found.add(next)) {
                queue.addAll(dependentsOf(next));
            }
        }
        return found;
    }

    // ---- scheduling ----

    /**
     * Stages eligible to run right now: not yet started, applicable to this run's
     * scenario, and with every dependency already successful.
     *
     * <p>Returns a set rather than one stage. That is the whole parallelism story —
     * the orchestrator asks "what can run?" and may get back three answers.
     */
    public Set<StageId> readyStages(TaskState state) {
        Set<StageId> ready = new LinkedHashSet<>();
        for (StageNode node : nodes.values()) {
            if (!node.appliesTo(state.scenario())) continue;          // <-- add this
            StageResult result = state.stage(node.id());
            if (result == null) continue;
            if (result.status() != StageStatus.PENDING && result.status() != StageStatus.STALE) continue;
            if (dependenciesSatisfied(node, state)) {
                ready.add(node.id());
            }
        }
        return ready;
    }

    private boolean dependenciesSatisfied(StageNode node, TaskState state) {
        for (StageId dep : node.dependsOn()) {
            StageResult r = state.stage(dep);
            if (r == null || !r.status().isSuccessful()) return false;
        }
        return true;
    }

    /**
     * Stages that can never run now, because something they depend on failed.
     *
     * <p>Computed transitively, so a single failure marks the whole downstream
     * cone BLOCKED in one pass rather than the orchestrator discovering it one
     * stage at a time.
     */
    public Set<StageId> blockedBy(StageId failed) {
        return transitiveDependentsOf(failed);
    }

    // ---- validation ----

    private static Map<StageId, Set<StageId>> buildReverseEdges(Map<StageId, StageNode> nodes) {
        Map<StageId, Set<StageId>> reverse = new HashMap<>();
        for (StageNode n : nodes.values()) {
            for (StageId dep : n.dependsOn()) {
                reverse.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(n.id());
            }
        }
        return reverse;
    }

    private void validateDependenciesExist() {
        for (StageNode n : nodes.values()) {
            for (StageId dep : n.dependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            n.id() + " depends on " + dep + ", which is not declared in this graph");
                }
            }
        }
    }

    /** Depth-first cycle detection. Reports the path, because "there is a cycle" is not actionable. */
    private void detectCycles() {
        Set<StageId> visited = new HashSet<>();
        Set<StageId> inProgress = new LinkedHashSet<>();
        for (StageId id : nodes.keySet()) {
            visit(id, visited, inProgress, new ArrayList<>());
        }
    }

    private void visit(StageId id, Set<StageId> visited, Set<StageId> inProgress, List<StageId> path) {
        if (visited.contains(id)) return;
        if (!inProgress.add(id)) {
            List<StageId> cycle = new ArrayList<>(path);
            cycle.add(id);
            throw new IllegalArgumentException("workflow graph contains a cycle: " + cycle);
        }
        path.add(id);
        for (StageId dep : nodes.get(id).dependsOn()) {
            visit(dep, visited, inProgress, path);
        }
        path.remove(path.size() - 1);
        inProgress.remove(id);
        visited.add(id);
    }
}