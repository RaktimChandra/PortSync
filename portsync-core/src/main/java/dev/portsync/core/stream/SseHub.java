package dev.portsync.core.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fan-out of live updates to every connected dashboard over Server-Sent Events.
 *
 * <p>Kafka consumer threads must never block on a slow browser, so {@link #broadcast} only hands
 * the message to a single sender thread through a bounded queue. If the queue is full (clients far
 * behind) the message is dropped and counted; the dashboard re-fetches state on reconnect anyway.
 * A comment line is sent every 15 s so idle connections survive proxies and load balancers.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10_000), r -> {
                Thread t = new Thread(r, "sse-sender");
                t.setDaemon(true);
                return t;
            }, (r, executor) -> dropped.incrementAndGet());

    /** Registers a new client. Timeout 0 = the connection stays open until the client leaves. */
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").reconnectTime(3_000)
                    .data(new Hello(sequence.get()), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** Queues a message for every client. Never blocks the caller. */
    public void broadcast(LiveMessage message) {
        if (emitters.isEmpty()) {
            return;
        }
        long id = sequence.incrementAndGet();
        sender.execute(() -> send(id, message));
    }

    private void send(long id, LiveMessage message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(id)).name(message.name())
                        .data(message.data(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        if (emitters.isEmpty()) {
            return;
        }
        sender.execute(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
                }
            }
        });
    }

    public int clientCount() {
        return emitters.size();
    }

    public long droppedMessages() {
        return dropped.get();
    }

    @PreDestroy
    void shutdown() {
        emitters.forEach(SseEmitter::complete);
        sender.shutdownNow();
        log.info("SSE hub stopped");
    }

    record Hello(long sequence) {
    }
}
