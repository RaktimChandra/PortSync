// Server-Sent Events client with automatic reconnect.
//
// The browser's EventSource already retries on network errors, but it gives up for good when the
// server answers with an error status (for example while portsync-core restarts behind nginx).
// This wrapper owns the lifecycle: on any error it closes the source and reconnects with
// exponential back-off (1 s, 2 s, 4 s ... capped at 15 s), and it reports every state change so
// the UI can show it and re-fetch everything once connected again.

export type StreamStatus = 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface StreamMessage {
  name: string;
  data: unknown;
}

export interface EventStreamOptions {
  url: string;
  names: string[];
  onMessage: (message: StreamMessage) => void;
  onStatus: (status: StreamStatus) => void;
  /** Injected for tests; defaults to the browser's EventSource. */
  createSource?: (url: string) => EventSource;
  baseDelayMs?: number;
  maxDelayMs?: number;
}

export class EventStream {
  private source: EventSource | null = null;
  private attempts = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;

  constructor(private readonly options: EventStreamOptions) {}

  start(): void {
    this.stopped = false;
    this.connect('connecting');
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.source?.close();
    this.source = null;
    this.options.onStatus('closed');
  }

  /** Delay before the next reconnect attempt (exposed for tests). */
  nextDelay(): number {
    const base = this.options.baseDelayMs ?? 1000;
    const max = this.options.maxDelayMs ?? 15000;
    return Math.min(max, base * 2 ** Math.max(0, this.attempts - 1));
  }

  private connect(status: StreamStatus): void {
    if (this.stopped) return;
    this.options.onStatus(status);
    const create = this.options.createSource ?? ((url: string) => new EventSource(url));
    const source = create(this.options.url);
    this.source = source;

    source.onopen = () => {
      this.attempts = 0;
      this.options.onStatus('open');
    };
    source.onerror = () => {
      source.close();
      if (this.source !== source || this.stopped) return;
      this.source = null;
      this.attempts++;
      this.options.onStatus('reconnecting');
      this.timer = setTimeout(() => this.connect('reconnecting'), this.nextDelay());
    };
    for (const name of this.options.names) {
      source.addEventListener(name, (event) => {
        const raw = (event as MessageEvent<string>).data;
        let data: unknown = raw;
        try {
          data = JSON.parse(raw);
        } catch {
          // keep raw text
        }
        this.options.onMessage({ name, data });
      });
    }
  }
}
