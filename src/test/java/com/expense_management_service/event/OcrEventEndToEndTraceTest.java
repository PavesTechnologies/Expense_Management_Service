package com.expense_management_service.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.expense_management_service.config.AsyncConfig;
import com.expense_management_service.service.OCRService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Real, end-to-end proof — not a hypothesis — of whether the production
 * {@code ReceiptUploadedEvent} chain actually fires: a {@code @Transactional} method publishes
 * the event, the transaction commits, {@code @TransactionalEventListener(AFTER_COMMIT)} dispatches
 * it, {@code @Async} submits it to the real {@code ocrTaskExecutor} bean, and
 * {@code OcrEventListener.onReceiptUploaded()} runs and calls {@code OCRService.processReceipt()}.
 * <p>
 * Nothing about this mechanism is mocked or reimplemented: {@link AsyncConfig} and
 * {@link OcrEventListener} are the exact, unmodified production classes, registered in a real
 * {@link AnnotationConfigApplicationContext} with {@code @EnableTransactionManagement} and
 * {@link TransactionalEventListenerFactory} — the same infrastructure bean Spring Boot's own
 * auto-configuration registers in the real app, required for {@code @TransactionalEventListener}
 * to actually honor {@code phase = AFTER_COMMIT} instead of silently degrading to a plain
 * synchronous {@code @EventListener}.
 * <p>
 * Deliberately needs no real database, AWS account, or network access: the
 * {@link PlatformTransactionManager} is a minimal no-op subclass of Spring's own
 * {@link AbstractPlatformTransactionManager} — only the JDBC-facing hook methods are stubbed;
 * the actual transaction-synchronization/{@code afterCommit()} dispatch machinery
 * {@code @TransactionalEventListener} depends on lives in the base class and is exercised for
 * real. Only {@link OCRService} is a Mockito mock, since this test is about the wiring between
 * upload and OCR, not OCR's own business logic (covered separately by {@code OCRServiceImplTest}).
 */
class OcrEventEndToEndTraceTest {

    @Configuration
    @EnableTransactionManagement
    @EnableAsync
    static class TraceTestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                    // No real resource to begin a transaction against — see class javadoc.
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                    // No real resource to commit — the point of this test is the event/async
                    // dispatch machinery around the commit, not the commit itself.
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }

        /**
         * Without this, {@code @TransactionalEventListener} would silently fall back to being
         * treated as a plain, immediate {@code @EventListener} by the default event listener
         * factory — exactly the kind of silent, undetectable degradation this whole
         * investigation is about. Spring Boot registers this automatically in the real app;
         * a bare {@link AnnotationConfigApplicationContext} does not, so it must be explicit here.
         */
        @Bean
        TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        TraceUploadService traceUploadService(ApplicationEventPublisher publisher) {
            return new TraceUploadService(publisher);
        }
    }

    /** Mirrors the exact shape of {@code ReceiptServiceImpl.upload()}'s relevant tail — save, publish, return. */
    static class TraceUploadService {
        private static final org.slf4j.Logger log = LoggerFactory.getLogger(TraceUploadService.class);
        private final ApplicationEventPublisher publisher;

        TraceUploadService(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        void simulateUpload(UUID receiptId) {
            log.info("[TRACE] Entering simulateUpload()");
            log.info("[TRACE] Receipt saved (simulated — no real DB needed for this test)");
            log.info("[TRACE] Publishing ReceiptUploadedEvent for receipt {}", receiptId);
            publisher.publishEvent(new ReceiptUploadedEvent(receiptId));
            log.info("[TRACE] publishEvent() returned");
        }
    }

    private AnnotationConfigApplicationContext context;
    private ListAppender<ILoggingEvent> logCapture;
    private OCRService ocrService;

    @BeforeEach
    void setUp() {
        ocrService = mock(OCRService.class);

        context = new AnnotationConfigApplicationContext();
        context.register(TraceTestConfig.class, AsyncConfig.class, OcrEventListener.class);
        context.registerBean(OCRService.class, () -> ocrService);
        context.refresh();

        logCapture = new ListAppender<>();
        logCapture.start();
        Logger appLogger = (Logger) LoggerFactory.getLogger("com.expense_management_service");
        appLogger.addAppender(logCapture);
        appLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger appLogger = (Logger) LoggerFactory.getLogger("com.expense_management_service");
        appLogger.detachAppender(logCapture);
        context.close();
    }

    @Test
    void entireEventChain_firesEndToEnd_usingRealSpringTransactionAndAsyncMachinery() {
        UUID receiptId = UUID.randomUUID();
        TraceUploadService uploadService = context.getBean(TraceUploadService.class);

        uploadService.simulateUpload(receiptId);

        // processReceipt runs on a background thread — wait for it, don't assume it's instant.
        verify(ocrService, timeout(3000)).processReceipt(receiptId);

        List<String> messages = logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        System.out.println("=== Captured execution trace ===");
        messages.forEach(System.out::println);
        System.out.println("=================================");

        assertThat(messages).anyMatch(m -> m.contains("Entering simulateUpload()"));
        assertThat(messages).anyMatch(m -> m.contains("Publishing ReceiptUploadedEvent"));
        assertThat(messages).anyMatch(m -> m.contains("publishEvent() returned"));
        assertThat(messages).anyMatch(m -> m.contains("ReceiptUploadedEvent received"));
        assertThat(messages).anyMatch(m -> m.contains("OCR task submitted"));
        assertThat(messages).anyMatch(m -> m.contains("OCR task started"));
        assertThat(messages).anyMatch(m -> m.contains("Invoking OCRService.processReceipt()"));
        assertThat(messages).anyMatch(m -> m.contains("OCRService.processReceipt() returned normally"));
        assertThat(messages).anyMatch(m -> m.contains("Exiting onReceiptUploaded()"));
    }
}
