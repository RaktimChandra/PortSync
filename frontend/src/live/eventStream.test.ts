import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EventStream, type StreamStatus } from './eventStream';

/** Minimal stand-in for the browser's EventSource. */
class FakeSource {
  static instances: FakeSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  listeners = new Map<string, (e: MessageEvent<string>) => void>();
  constructor(public url: string) {
    FakeSource.instances.push(this);
  }
  addEventListener(name: string, fn: (e: MessageEvent<string>) => void) {
    this.listeners.set(name, fn);
  }
  close() {
    this.closed = true;
  }
  emit(name: string, data: string) {
    this.listeners.get(name)?.({ data } as MessageEvent<string>);
  }
}

describe('EventStream', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSource.instances = [];
  });
  afterEach(() => vi.useRealTimers());

  function make() {
    const statuses: StreamStatus[] = [];
    const messages: unknown[] = [];
    const stream = new EventStream({
      url: '/api/stream',
      names: ['event'],
      onMessage: (m) => messages.push(m),
      onStatus: (s) => statuses.push(s),
      createSource: (url) => new FakeSource(url) as unknown as EventSource,
      baseDelayMs: 1000,
      maxDelayMs: 4000,
    });
    return { stream, statuses, messages };
  }

  it('parses JSON messages', () => {
    const { stream, messages } = make();
    stream.start();
    FakeSource.instances[0].emit('event', '{"type":"GATE_IN"}');
    expect(messages).toEqual([{ name: 'event', data: { type: 'GATE_IN' } }]);
  });

  it('reconnects with exponential back-off after errors and resets after a successful open', () => {
    const { stream, statuses } = make();
    stream.start();
    FakeSource.instances[0].onerror?.();
    expect(FakeSource.instances[0].closed).toBe(true);
    expect(statuses.at(-1)).toBe('reconnecting');

    vi.advanceTimersByTime(999);
    expect(FakeSource.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSource.instances).toHaveLength(2);

    FakeSource.instances[1].onerror?.();
    vi.advanceTimersByTime(1999);
    expect(FakeSource.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeSource.instances).toHaveLength(3);

    FakeSource.instances[2].onerror?.();
    vi.advanceTimersByTime(4000);
    expect(FakeSource.instances).toHaveLength(4);

    FakeSource.instances[3].onopen?.();
    expect(statuses.at(-1)).toBe('open');
    expect(stream.nextDelay()).toBe(1000);
  });

  it('stops reconnecting once stopped', () => {
    const { stream, statuses } = make();
    stream.start();
    FakeSource.instances[0].onerror?.();
    stream.stop();
    vi.advanceTimersByTime(60_000);
    expect(FakeSource.instances).toHaveLength(1);
    expect(statuses.at(-1)).toBe('closed');
  });
});
