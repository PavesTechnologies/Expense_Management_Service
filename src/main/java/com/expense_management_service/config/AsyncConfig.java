package com.expense_management_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Backs {@code @Async("ocrTaskExecutor")} (see {@code OcrEventListener}) with a bounded pool.
 * Spring's default {@code @Async} executor ({@code SimpleAsyncTaskExecutor}) spawns an unbounded
 * thread per task — not safe for a call chain that ends in a network call to AWS Textract.
 * <p>
 * Two deliberate hardening choices, both in response to receipts observed stuck at
 * {@code UPLOADED} forever with no application-level error logged anywhere:
 * <ul>
 *     <li>{@link TaskDecorator} logs submission and start/finish around every task — if
 *     "submitted" is ever logged without a matching "started", that's direct evidence the task
 *     was rejected or lost between submission and execution, something no log statement inside
 *     {@code OcrEventListener} itself could ever catch (that code never runs in that case).</li>
 *     <li>{@link ThreadPoolExecutor.CallerRunsPolicy} instead of the default {@code AbortPolicy}:
 *     if the pool and queue are ever saturated, the task still runs — synchronously, on the
 *     thread that would otherwise have thrown {@code TaskRejectedException} — rather than being
 *     silently dropped. Spring's transaction manager only logs (at its own log category, not
 *     ours) an exception thrown by an {@code afterCommit()} synchronization; it never rethrows
 *     it, so a rejected task previously vanished without a trace in application logs.</li>
 * </ul>
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    @Bean(name = "ocrTaskExecutor")
    public Executor ocrTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ocr-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(this::withSubmissionLogging);
        executor.initialize();
        return executor;
    }

    private Runnable withSubmissionLogging(Runnable task) {
        log.info("[OCR] OCR task submitted to executor");
        return () -> {
            log.info("[OCR] OCR task started on thread {}", Thread.currentThread().getName());
            try {
                task.run();
            } finally {
                log.info("[OCR] OCR task finished on thread {}", Thread.currentThread().getName());
            }
        };
    }
}
