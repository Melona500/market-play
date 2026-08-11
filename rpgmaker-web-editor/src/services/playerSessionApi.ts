export interface PlayerSessionConnection {
  sessionId: string;
  playerName: string;
  ownerUuid: string;
  expiresAt: number;
  admin: boolean;
}

export interface ServerDialogueSummary {
  name: string;
  title: string;
  revision: string;
  pages: number;
}

export interface ServerDialogueDocument {
  name: string;
  revision: string;
  dialogue: Record<string, unknown>;
  ownerUuid?: string;
  publisher?: string;
}

export interface PublicDialogueSummary extends ServerDialogueSummary {
  ownerUuid: string;
  publisher: string;
}

export interface ServerItemSummary {
  name: string;
  reference: string;
  title: string;
  material: string;
  captured: boolean;
}

export class RevisionConflictError extends Error {
  constructor(readonly serverRevision: string) {
    super('서버 데이터가 웹에서 불러온 버전보다 최신입니다.');
  }
}

const SESSION_KEY = 'rpgmaker.player-session.v1';
let connectionPromise: Promise<PlayerSessionConnection | undefined> | undefined;

function apiRoot() {
  const configured = import.meta.env.VITE_RPGMAKER_API_URL as string | undefined;
  if (configured?.trim()) return configured.replace(/\/$/, '');

  // Local Vite development must always talk to the Paper Web API, not to Vite's
  // own origin. This also survives Vite moving from 5173 to another dev port.
  const localHost = ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname);
  if (localHost) return 'http://127.0.0.1:25567/api/v1';

  return `${window.location.origin}/api/v1`;
}

export function capturePlayerSession(): string | undefined {
  const url = new URL(window.location.href);
  const incoming = url.searchParams.get('session')?.trim();
  if (incoming) {
    sessionStorage.setItem(SESSION_KEY, incoming);
    url.searchParams.delete('session');
    window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
    return incoming;
  }
  return sessionStorage.getItem(SESSION_KEY) || undefined;
}

export function clearPlayerSession() {
  sessionStorage.removeItem(SESSION_KEY);
}

export function connectPlayerSession() {
  connectionPromise ??= connectPlayerSessionOnce().finally(() => {
    connectionPromise = undefined;
  });
  return connectionPromise;
}

async function connectPlayerSessionOnce(): Promise<PlayerSessionConnection | undefined> {
  const stored = capturePlayerSession();
  if (stored) {
    try {
      return await new PlayerSessionApiClient(stored).connect();
    } catch {
      clearPlayerSession();
    }
  }

  const response = await fetch(`${apiRoot()}/session/auto`, { method: 'POST' });
  if (response.status === 404) return undefined;
  if (!response.ok) throw new Error(`자동 서버 연결 실패 (HTTP ${response.status})`);
  const result = (await response.json()) as PlayerSessionConnection;
  sessionStorage.setItem(SESSION_KEY, result.sessionId);
  return result;
}

export class PlayerSessionApiClient {
  constructor(private readonly sessionId: string) {}

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${apiRoot()}${path}`, {
        ...init,
        headers: {
          'X-RPGMaker-Session': this.sessionId,
          'Content-Type': 'application/json',
          ...(init?.headers ?? {}),
        },
      });
    } catch (error) {
      throw new Error(
        `Minecraft 서버 Web API에 연결할 수 없습니다 (${apiRoot()}). 서버가 실행 중이고 web-api가 활성화되어 있는지 확인하세요.`,
        { cause: error },
      );
    }

    const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
    if (response.status === 409) throw new RevisionConflictError(String(body.serverRevision ?? ''));
    if (!response.ok) {
      const issues = Array.isArray(body.issues) ? ` ${body.issues.join(' / ')}` : '';
      const errorCode = String(body.error ?? `HTTP ${response.status}`);
      if (response.status === 401) throw new Error('웹 연결 세션이 만료되었거나 유효하지 않습니다. 게임에서 /rpgmaker web을 다시 실행하세요.');
      if (response.status === 403 && errorCode === 'origin_not_allowed')
        throw new Error('현재 웹 주소가 서버 web-api.allowed-origins에 허용되어 있지 않습니다.');
      throw new Error(`${errorCode}${issues}`);
    }
    return body as T;
  }

  async connect(): Promise<PlayerSessionConnection> {
    const result = await this.request<{
      connected: boolean;
      playerName: string;
      ownerUuid: string;
      expiresAt: number;
      admin: boolean;
    }>('/me');
    return {
      sessionId: this.sessionId,
      playerName: result.playerName,
      ownerUuid: result.ownerUuid,
      expiresAt: result.expiresAt,
      admin: result.admin,
    };
  }

  async listDialogues(ownerUuid: string) {
    const result = await this.request<{ dialogues: ServerDialogueSummary[] }>(
      `/dialogues/${encodeURIComponent(ownerUuid)}`,
    );
    return result.dialogues;
  }

  async listItems(ownerUuid: string) {
    const result = await this.request<{ items: ServerItemSummary[] }>(
      `/items/${encodeURIComponent(ownerUuid)}`,
    );
    return result.items;
  }

  async listPublicDialogues() {
    const result = await this.request<{ dialogues: PublicDialogueSummary[] }>('/public-dialogues');
    return [...result.dialogues].sort((left, right) =>
      (left.title || left.name).localeCompare(right.title || right.name, 'ko'),
    );
  }

  getPublicDialogue(name: string) {
    return this.request<ServerDialogueDocument>(`/public-dialogues/${encodeURIComponent(name)}`);
  }

  savePublicDialogue(
    ownerUuid: string,
    name: string,
    expectedRevision: string | undefined,
    dialogue: Record<string, unknown>,
  ) {
    return this.request<{ saved: boolean; revision: string }>(
      `/public-dialogues/${encodeURIComponent(ownerUuid)}/${encodeURIComponent(name)}`,
      { method: 'PUT', body: JSON.stringify({ expectedRevision: expectedRevision ?? '', dialogue }) },
    );
  }

  getDialogue(ownerUuid: string, name: string) {
    return this.request<ServerDialogueDocument>(
      `/dialogues/${encodeURIComponent(ownerUuid)}/${encodeURIComponent(name)}`,
    );
  }

  saveDialogue(
    ownerUuid: string,
    name: string,
    expectedRevision: string | undefined,
    dialogue: Record<string, unknown>,
  ) {
    return this.request<{ saved: boolean; revision: string }>(
      `/dialogues/${encodeURIComponent(ownerUuid)}/${encodeURIComponent(name)}`,
      {
        method: 'PUT',
        body: JSON.stringify({ expectedRevision: expectedRevision ?? '', dialogue }),
      },
    );
  }

  reloadDialogue(ownerUuid: string, name: string) {
    return this.request<{ reloaded: boolean; revision: string }>(
      `/dialogues/${encodeURIComponent(ownerUuid)}/${encodeURIComponent(name)}/reload`,
      { method: 'POST', body: '{}' },
    );
  }

  deleteDialogue(ownerUuid: string, name: string, revision?: string) {
    return this.request<{ deleted: boolean }>(
      `/dialogues/${encodeURIComponent(ownerUuid)}/${encodeURIComponent(name)}`,
      {
        method: 'DELETE',
        headers: revision ? { 'If-Match': revision } : undefined,
      },
    );
  }

  validate(dialogue: Record<string, unknown>) {
    return this.request<{ valid: boolean; issues: string[] }>('/validate', {
      method: 'POST',
      body: JSON.stringify({ dialogue }),
    });
  }
}
