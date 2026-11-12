package com.expense_management_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code ocrTaskExecutor} genuinely dispatches work onto a different thread — the one
 * property a pure Mockito test of {@code OcrEventListener} can't demonstrate, since calling a
 * method directly always runs it on the calling thread regardless of its annotations. Also
 * covers the two hardening measures added after receipts were observed stuck at UPLOADED with
 * no application-level error logged anywhere: the submission/start/finish log decorator, and a
 * rejection policy that runs an overflow task instead of silently dropping it.
 */
class AsyncConfigTest {

    @Test
    void ocrTaskExecutor_runsSubmittedWorkOnADifferentThread() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().ocrTaskExecutor();
        try {
            Thread callingThread = Thread.currentThread();

            CompletableFuture<Thread> executionThread = new CompletableFuture<>();
            executor.execute(() -> executionThread.complete(Thread.currentThread()));

            Thread threadThatRanTheTask = executionThread.get(2, TimeUnit.SECONDS);
            assertThat(threadThatRanTheTask).isNotEqualTo(callingThread);
            assertThat(threadThatRanTheTask.getName()).startsWith("ocr-async-");
        } finally {
            // Spring shuts this down via DisposableBean when it's a container-managed bean;
            // here it's constructed by hand, so it must be shut down explicitly — otherwise its
            // non-daemon threads keep running and the test JVM never exits.
            executor.shutdown();
        }
    }

    @Test
    void ocrTaskExecutor_usesCallerRunsPolicy_soAnOverflowTaskIsNeverSilentlyDropped() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().ocrTaskExecutor();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void ocrTaskExecutor_submissionLoggingDecorator_doesNotPreventTaskExecution() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().ocrTaskExecutor();
        try {
            CompletableFuture<Boolean> ran = new CompletableFuture<>();
            executor.execute(() -> ran.complete(true));

            assertThat(ran.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }
    }
}
