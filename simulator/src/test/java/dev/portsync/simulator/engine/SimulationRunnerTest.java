package dev.portsync.simulator.engine;

import dev.portsync.simulator.SimulatorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SimulationRunnerTest {

    private final TerminalSimulation simulation = mock(TerminalSimulation.class);
    private final SimClock clock = new SimClock(Clock.systemUTC(), 1.0, Instant.parse("2026-01-01T00:00:00Z"));

    @SuppressWarnings("unchecked")
    private SimulationRunner runningRunner() throws Exception {
        SimulationRunner runner = new SimulationRunner(simulation, mock(EventSink.class), clock, mock(SimulatorProperties.class),
                mock(KafkaTemplate.class));
        Field running = SimulationRunner.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(runner, true);
        return runner;
    }

    @Test
    void pauseReturnsOnlyAfterATickUnderWayHasFinished() throws Exception {
        SimulationRunner runner = runningRunner();
        CountDownLatch tickEntered = new CountDownLatch(1);
        AtomicLong tickFinished = new AtomicLong();
        doAnswer(inv -> {
            tickEntered.countDown();
            TimeUnit.MILLISECONDS.sleep(300); // a slow tick: lots of events to publish
            tickFinished.set(System.nanoTime());
            return 0;
        }).when(simulation).tick(any());

        Thread ticker = Thread.ofPlatform().start(runner::tick);
        assertThat(tickEntered.await(5, TimeUnit.SECONDS)).isTrue();
        runner.pause();
        long pauseReturned = System.nanoTime();
        ticker.join(5_000);

        assertThat(clock.isPaused()).isTrue();
        assertThat(tickFinished.get()).as("the tick finished before pause() returned").isPositive()
                .isLessThanOrEqualTo(pauseReturned);
    }

    @Test
    void noTickRunsWhilePaused() throws Exception {
        SimulationRunner runner = runningRunner();
        runner.pause();
        runner.tick();
        verify(simulation, never()).tick(any());
    }
}
