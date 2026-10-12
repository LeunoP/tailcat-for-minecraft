package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedHelperProcessTest {
    @Test void malformedEventAfterReadyReportsFailureAndReapsChild() throws Exception {
        checkProtocolFailure("malformed");
    }

    @Test void oversizedEventReportsFailureAndReapsChild() throws Exception {
        checkProtocolFailure("oversized");
    }

    private void checkProtocolFailure(String mode) throws Exception {
        AtomicInteger failures = new AtomicInteger();
        CompletableFuture<HelperEvent> failure = new CompletableFuture<>();
        ManagedHelperProcess helper = ManagedHelperProcess.start(child(mode), event -> {
            if (event.type().equals("error")) { failures.incrementAndGet(); failure.complete(event); }
        }, null);
        try {
            assertEquals("mcl1_test", helper.ready(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS).invite());
            assertEquals("protocol_error", failure.get(5, TimeUnit.SECONDS).code());
            helper.closed().get(8, TimeUnit.SECONDS);
            assertFalse(helper.isAlive());
            assertEquals(1, failures.get());
        } finally { helper.close(); }
    }

    @Test void unexpectedExitFailsStartup() throws Exception {
        CompletableFuture<HelperEvent> failure = new CompletableFuture<>();
        ManagedHelperProcess helper = ManagedHelperProcess.start(child("exit"), event -> {
            if (event.type().equals("error")) failure.complete(event);
        }, null);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> helper.ready(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
            assertEquals("unexpected_exit", failure.get(5, TimeUnit.SECONDS).code());
            helper.closed().get(5, TimeUnit.SECONDS);
        } finally { helper.close(); }
    }

    @Test void deliberateCloseDoesNotReportFailure() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        ManagedHelperProcess helper = ManagedHelperProcess.start(child("normal"), event -> {
            if (event.type().equals("error")) failures.incrementAndGet();
        }, null);
        try {
            helper.ready(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
            helper.close();
            helper.closed().get(8, TimeUnit.SECONDS);
            assertEquals(0, failures.get());
        } finally { helper.close(); }
    }

    @Test void closeDoesNotBlockEvenWhenChildIgnoresEof() throws Exception {
        ManagedHelperProcess helper = ManagedHelperProcess.start(child("slow-close"), event -> {}, null);
        try {
            helper.ready(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
            assertTimeout(Duration.ofMillis(250), helper::close);
            assertFalse(helper.isAlive());
            helper.closed().get(8, TimeUnit.SECONDS);
        } finally { helper.close(); }
    }

    @Test void launchFailureDoesNotCreateManagedProcess() {
        assertThrows(java.io.IOException.class, () -> ManagedHelperProcess.start(
                new ProcessBuilder("tailcat-test-executable-that-does-not-exist"), event -> {}, null));
    }

    @Test void closeCompletesWhenProcessExitNotificationNeverArrives() throws Exception {
        Process child = child("normal").start();
        Process missingNotification = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return child.getOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return child.getInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return child.getErrorStream(); }
            @Override public int waitFor() throws InterruptedException { return child.waitFor(); }
            @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                return child.waitFor(timeout, unit);
            }
            @Override public int exitValue() { return child.exitValue(); }
            @Override public boolean isAlive() { return child.isAlive(); }
            @Override public void destroy() { child.destroy(); }
            @Override public Process destroyForcibly() { child.destroyForcibly(); return this; }
            @Override public CompletableFuture<Process> onExit() { return new CompletableFuture<>(); }
        };
        ManagedHelperProcess helper = new ManagedHelperProcess(missingNotification, event -> {}, null);
        try {
            helper.ready(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
            helper.close();
            helper.closed().get(8, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
        } finally {
            helper.close();
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void bundledHelperKeepsInvalidInviteErrorInsteadOfExitRace() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.arch").equals("amd64"));
        java.net.URL resource = getClass().getResource("/assets/mclink/native/windows-amd64/mclink-helper.exe");
        assertNotNull(resource);
        Path helperPath = Path.of(resource.toURI());
        for (int attempt = 0; attempt < 10; attempt++) {
            CompletableFuture<HelperEvent> failure = new CompletableFuture<>();
            ManagedHelperProcess helper = ManagedHelperProcess.start(new ProcessBuilder(
                    helperPath.toString(), "join", "--invite", "mcl1_invalid"), event -> {
                if (event.type().equals("error")) failure.complete(event);
            }, null);
            try {
                assertEquals("invalid_invite", failure.get(5, TimeUnit.SECONDS).code());
                helper.closed().get(8, TimeUnit.SECONDS);
            } finally { helper.close(); }
        }
    }

    private static ProcessBuilder child(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classes = Path.of(HelperProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(java.toString(), "-cp", classes, HelperProcessFixture.class.getName(), mode);
    }
}
