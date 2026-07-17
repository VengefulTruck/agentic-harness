package com.jay.agentic.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists a run to disk so it can be inspected, audited, and resumed.
 *
 * <p>Resumability is not a convenience here. A run parks at AWAITING_APPROVAL
 * waiting on a human who may not answer for an hour, and a harness that
 * requires the JVM to stay alive across that wait has not implemented a human
 * checkpoint — it has implemented a blocking prompt. Being able to write state,
 * exit, and pick the run back up is what makes the approval gate real.
 *
 * <p>Writes go to a temp file and are then atomically moved into place, so a
 * crash mid-write cannot leave a half-written file that parses as valid JSON
 * but describes a state that never existed.
 */
public final class StateStore {

    private final Path runsDir;
    private final ObjectMapper mapper;

    public StateStore(Path runsDir) {
        this.runsDir = runsDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path pathFor(String runId) {
        return runsDir.resolve(runId).resolve("state.json");
    }

    /** Writes the run state atomically. Safe to call after every stage transition. */
    public void save(RunSnapshot snapshot) {
        try {
            Path target = pathFor(snapshot.runId());
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling("state.json.tmp");
            mapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save run " + snapshot.runId(), e);
        }
    }

    public RunSnapshot load(String runId) {
        try {
            return mapper.readValue(pathFor(runId).toFile(), RunSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load run " + runId, e);
        }
    }

    public boolean exists(String runId) {
        return Files.exists(pathFor(runId));
    }
}