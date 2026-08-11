export interface ServerConnectionConfig {
  baseUrl: string;
  token: string;
  ownerUuid: string;
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
}

export class RevisionConflictError extends Error {
  constructor(readonly serverRevision: string) {
    super('서버 데이터가 웹에서 불러온 버전보다 최신입니다.');
  }
}

export class MinecraftApiClient {
  constructor(private readonly config: ServerConnectionConfig) {}

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${this.config.baseUrl.replace(/\/$/, '')}/api/v1${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.config.token}`,
        'Content-Type': 'application/json',
        ...(init?.headers ?? {}),
      },
    });
    const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
    if (response.status === 409) throw new RevisionConflictError(String(body.serverRevision ?? ''));
    if (!response.ok) {
      const issues = Array.isArray(body.issues) ? ` ${body.issues.join(' / ')}` : '';
      throw new Error(`${String(body.error ?? `HTTP ${response.status}`)}${issues}`);
    }
    return body as T;
  }

  status() {
    return this.request<{
      connected: boolean;
      apiVersion: number;
      pluginVersion: string;
      characterManifestVersion: number;
    }>('/status');
  }

  async listDialogues() {
    const result = await this.request<{ dialogues: ServerDialogueSummary[] }>(
      `/dialogues/${encodeURIComponent(this.config.ownerUuid)}`,
    );
    return result.dialogues;
  }

  getDialogue(name: string) {
    return this.request<ServerDialogueDocument>(
      `/dialogues/${encodeURIComponent(this.config.ownerUuid)}/${encodeURIComponent(name)}`,
    );
  }

  saveDialogue(name: string, expectedRevision: string | undefined, dialogue: Record<string, unknown>) {
    return this.request<{ saved: boolean; revision: string }>(
      `/dialogues/${encodeURIComponent(this.config.ownerUuid)}/${encodeURIComponent(name)}`,
      {
        method: 'PUT',
        body: JSON.stringify({ expectedRevision: expectedRevision ?? '', dialogue }),
      },
    );
  }

  reloadDialogue(name: string) {
    return this.request<{ reloaded: boolean; revision: string }>(
      `/dialogues/${encodeURIComponent(this.config.ownerUuid)}/${encodeURIComponent(name)}/reload`,
      { method: 'POST', body: '{}' },
    );
  }

  deleteDialogue(name: string, revision?: string) {
    return this.request<{ deleted: boolean }>(
      `/dialogues/${encodeURIComponent(this.config.ownerUuid)}/${encodeURIComponent(name)}`,
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

const STORAGE_KEY = 'rpgmaker.server-connection.v1';

export function loadServerConnection(): ServerConnectionConfig {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Partial<ServerConnectionConfig>;
    return {
      baseUrl: value.baseUrl || 'http://127.0.0.1:25567',
      token: value.token || 'dev-local-token-change-me',
      ownerUuid: value.ownerUuid || '',
    };
  } catch {
    return { baseUrl: 'http://127.0.0.1:25567', token: 'dev-local-token-change-me', ownerUuid: '' };
  }
}

export function saveServerConnection(config: ServerConnectionConfig) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
}
