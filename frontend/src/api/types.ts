// Shapes returned by portsync-core (see Views.java). Times are ISO-8601 strings in simulated time.

export interface BlockSummary {
  block: string;
  used: number;
  capacity: number;
  utilisation: number;
}

export interface Overview {
  simulatedTime: string;
  clockSynchronised: boolean;
  vesselsAtBerth: number;
  vesselsWaiting: number;
  craneMovesLastHour: number;
  yardUtilisation: number;
  blocks: BlockSummary[];
  openAlerts: number;
  acknowledgedAlerts: number;
  avgTruckTurnMinutes: number | null;
  trucksInTerminal: number;
  cranesFaulted: number;
  eventsProcessed: number;
  dlqEvents: number;
}

export type CraneStatus = 'IDLE' | 'WORKING' | 'FAULTED';

export interface Crane {
  id: string;
  status: CraneStatus;
  berthId: string | null;
  assignedAt: string | null;
  faultCode: string | null;
  faultedSince: string | null;
  lastHeartbeatAt: string | null;
  movesLastHour: number;
}

export interface Vessel {
  id: string;
  name: string;
  voyage: string;
  status: 'WAITING' | 'AT_BERTH' | 'DEPARTED';
  berthId: string | null;
  arrivedAt: string;
  berthedAt: string | null;
  departedAt: string | null;
  dischargePlanned: number;
  discharged: number;
  loadPlanned: number;
  loaded: number;
}

export interface Berth {
  id: string;
  vessel: Vessel | null;
  cranes: Crane[];
}

export interface Slot {
  tier: number;
  containerNumber: string | null;
}

export interface Stack {
  row: number;
  bay: number;
  height: number;
  slots: Slot[];
}

export interface YardBlock {
  block: string;
  rows: number;
  bays: number;
  tiers: number;
  capacity: number;
  used: number;
  utilisation: number;
  stacks: Stack[];
}

export interface HistoryItem {
  eventId: string | null;
  type: string;
  occurredAt: string;
  description: string;
}

export interface Rejection {
  eventId: string | null;
  type: string | null;
  reason: string;
  detail: string | null;
  deadLetteredAt: string;
}

export interface ContainerDetail {
  number: string;
  valid: boolean;
  status: string;
  vesselId: string | null;
  location: { block: string; row: number; bay: number; tier: number; label: string } | null;
  lastEventAt: string | null;
  history: HistoryItem[];
  rejections: Rejection[];
}

export type AlertState = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

export interface Alert {
  id: number;
  rule: string;
  entityId: string;
  severity: 'WARNING' | 'CRITICAL';
  state: AlertState;
  message: string;
  observedValue: number | null;
  threshold: number | null;
  openedAt: string;
  lastSeenAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
  resolution: 'AUTO' | 'MANUAL' | null;
}

export interface EventItem {
  eventId: string;
  type: string;
  entityId: string;
  occurredAt: string;
  outcome: 'APPLIED' | 'IGNORED_STALE' | 'REJECTED' | 'DUPLICATE';
  reason: string | null;
  detail: string | null;
  topic: string | null;
  partition: number | null;
  offset: number | null;
  processedAt?: string;
}

export interface DlqItem {
  id: number;
  eventId: string | null;
  type: string | null;
  entityId: string | null;
  reason: string;
  detail: string | null;
  sourceTopic: string;
  sourcePartition: number;
  sourceOffset: number;
  deadLetteredAt: string;
  rawValue: string | null;
}

export interface TruckVisit {
  id: string;
  containerNumber: string;
  direction: 'DROP_OFF' | 'PICK_UP';
  lane: string;
  truckPlate: string | null;
  gateInAt: string;
  minutesInside: number;
}

export interface ApiError {
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  details?: Record<string, unknown>;
}
