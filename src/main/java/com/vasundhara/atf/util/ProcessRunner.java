package com.vasundhara.atf.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin, deadlock-safe wrapper around {@link ProcessBuilder}. stdout and stderr are
 * drained on separate threads so large output never blocks, and every invocation is
 * bounded by a timeout (the process is force-killed if it overruns).
 */
public final class ProcessRunner {

    private ProcessRunner() { }

    public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        public String combined() {
            if (stderr == null || stderr.isBlank()) return stdout;
            return stdout + (stdout.isBlank() ? "" : "\n") + stderr;
        }
    }

    /** Run a command and capture textual stdout/stderr, bounded by {@code timeoutSeconds}. */
    public static CommandResult run(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StreamGobbler out = new StreamGobbler(process.getInputStream());
            StreamGobbler err = new StreamGobbler(process.getErrorStream());
            out.start();
            err.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.join(1000);
                err.join(1000);
                return new CommandResult(-1, out.text(), err.text(), true);
            }
            out.join(2000);
            err.join(2000);
            return new CommandResult(process.exitValue(), out.text(), err.text(), false);
        } catch (IOException | InterruptedException e) {
            if (process != null) process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new CommandResult(-1, "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), false);
        }
    }

    /** Run a command and capture raw stdout bytes (e.g. for {@code screencap -p}). */
    public static byte[] runBinary(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StreamGobbler err = new StreamGobbler(process.getErrorStream());
            err.start();
            byte[] data;
            try (InputStream in = process.getInputStream()) {
                data = in.readAllBytes();
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            err.join(1000);
            if (!finished) {
                process.destroyForcibly();
                return new byte[0];
            }
            return data;
        } catch (IOException | InterruptedException e) {
            if (process != null) process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new byte[0];
        }
    }

    private static final class StreamGobbler extends Thread {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamGobbler(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                stream.transferTo(buffer);
            } catch (IOException ignored) {
                // stream closed on process exit — whatever we captured is kept
            }
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
