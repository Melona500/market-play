import type { ServerChoiceSettings, ServerPageSettings } from './serverSettings';

export type ProjectId = string;
export type DialogueId = string;
export type PageId = string;

export interface RPGProject {
  id: ProjectId;
  name: string;
  dialogues: Dialogue[];
  characters: CharacterDefinition[];
  variables: VariableDefinition[];
  items: ItemDefinition[];
  pendingServerDeletes?: PendingServerDialogueDelete[];
  createdAt: string;
  updatedAt: string;
  schemaVersion: number;
}

export interface PendingServerDialogueDelete {
  ownerUuid: string;
  remoteName: string;
  revision?: string;
}

export interface Dialogue {
  id: DialogueId;
  name: string;
  pages: DialoguePage[];
  startPageId: PageId;
  /** Dialogue to start after this dialogue reaches its natural end. */
  nextDialogueName?: string;
  editor?: {
    nodePositions?: Record<string, { x: number; y: number }>;
  };
  server?: {
    ownerUuid?: string;
    remoteName?: string;
    revision?: string;
    scope?: 'personal' | 'public';
    publisher?: string;
    raw?: Record<string, unknown>;
    lastSyncedAt?: string;
  };
}

export interface DialoguePage {
  id: PageId;
  editorLabel?: string;
  speaker: string;
  lines: [string, string, string, string];
  appearance: PageAppearance;
  choices: DialogueChoice[];
  flow: PageFlow;
  effects: PageEffect[];
  operationOnly?: boolean;
  server?: ServerPageSettings;
}

export interface PageAppearance {
  visible: boolean;
  speakerVisible: boolean;
  inheritPrevious: boolean;
  characterId?: string;
  gender?: 'male' | 'female';
  expression?: string;
}

/**
 * A player-facing choice on a page.
 *
 * `responsePages` are the follow-up dialogue pages shown after the player selects
 * this choice. Those pages may contain nested choices. `targetPageId` remains the
 * same-dialogue page jump used by the existing runtime; `targetDialogueName` is
 * the dialogue started after the selected branch finishes.
 */
export interface DialogueChoice {
  id: string;
  label: string;
  targetPageId?: PageId;
  targetDialogueName?: string;
  endAfterTarget?: boolean;
  speakerOverride?: string;
  responsePages?: DialogueChoiceResponsePage[];
  server?: ServerChoiceSettings;
}

/** Runtime-compatible follow-up page inside a choice response branch. */
export interface DialogueChoiceResponsePage {
  id: string;
  lines: [string, string, string, string];
  appearance: PageAppearance;
  choices: DialogueChoice[];
  server?: ServerPageSettings;
}

export interface PageFlow {
  nextPageId?: PageId;
  ending: boolean;
}

export interface CharacterDefinition {
  id: string;
  name: string;
  emoji: string;
  expressions: string[];
}

export interface VariableDefinition {
  id: string;
  name: string;
  type: 'number' | 'boolean' | 'string';
  initialValue: number | boolean | string;
}

export interface ItemDefinition {
  id: string;
  minecraftId: string;
  displayName: string;
  amount: number;
}

export type PageEffect =
  | { id: string; type: 'set-variable'; variableId: string; value: string | number | boolean }
  | { id: string; type: 'give-item'; itemId: string; amount: number };
