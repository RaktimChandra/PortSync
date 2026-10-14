import type {
  Alert, AlertState, ApiError, Berth, ContainerDetail, Crane, DlqItem, EventItem, Overview, TruckVisit, Vessel,
  YardBlock,
} from './types';

/** Error carrying the core's standard error body, so pages can show its code and message. */
export class ApiRequestError extends Error {
  constructor(public readonly status: number, public readonly body: ApiError | null) {
    super(body?.message ?? `Request failed with status ${status}`);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' }, ...init });
  if (!response.ok) {
    let body: ApiError | null = null;
    try {
      body = (await response.json()) as ApiError;
    } catch {
      body = null;
    }
    throw new ApiRequestError(response.status, body);
  }
  return (await response.json()) as T;
}

export const api = {
  overview: () => request<Overview>('/api/overview'),
  berths: () => request<Berth[]>('/api/berths'),
  cranes: () => request<Crane[]>('/api/cranes'),
  vessels: (status?: Vessel['status']) => request<Vessel[]>(`/api/vessels${status ? `?status=${status}` : ''}`),
  block: (block: string) => request<YardBlock>(`/api/yard/blocks/${encodeURIComponent(block)}`),
  container: (number: string) => request<ContainerDetail>(`/api/containers/${encodeURIComponent(number)}`),
  alerts: (states: AlertState[] = []) =>
    request<Alert[]>(`/api/alerts${states.length ? '?' + states.map((s) => `state=${s}`).join('&') : ''}`),
  acknowledge: (id: number) => request<Alert>(`/api/alerts/${id}/ack`, { method: 'POST' }),
  resolve: (id: number) => request<Alert>(`/api/alerts/${id}/resolve`, { method: 'POST' }),
  events: (limit = 100) => request<EventItem[]>(`/api/events?limit=${limit}`),
  dlq: (limit = 100) => request<DlqItem[]>(`/api/dlq?limit=${limit}`),
  truckVisits: () => request<TruckVisit[]>('/api/gate/visits'),
};
