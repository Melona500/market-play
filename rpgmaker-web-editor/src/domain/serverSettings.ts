import type { PageId } from './project';

export type ServerConditionMode = 'none' | 'variable' | 'item' | 'both' | 'any';
export type ServerVariableLogic = 'and' | 'or' | 'xor' | 'not';
export type ServerConditionOperator = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'is-set' | 'is-unset';

export interface ServerCondition {
  mode: ServerConditionMode;
  variable: string;
  value: string;
  operator: ServerConditionOperator;
  extraVariables: string;
  variableLogic: ServerVariableLogic;
  itemSpec: string;
  replacementLines: [string, string, string, string];
}

export interface ServerEffects {
  giveItems: string;
  takeItems: string;
  variablesSet: string;
  variablesDelete: string;
  chatInputVariable: string;
  sounds: string;
  message: string;
  messageColor: string;
  returnTarget: string;
  serverCommand: string;
  commandTarget: 'player' | 'all' | 'nearest';
}

export interface ServerPageSettings {
  operationOnly: boolean;
  displayCondition: ServerCondition;
  flow: {
    nextPageId?: PageId;
    conditionalTargetPageId?: PageId;
    conditionalTiming: 'before' | 'after';
    ending: boolean;
    condition: ServerCondition;
  };
  effects: ServerEffects;
}

export interface ServerChoiceSettings {
  condition: ServerCondition;
}

export const emptyCondition = (): ServerCondition => ({
  mode: 'none',
  variable: '',
  value: '',
  operator: 'eq',
  extraVariables: '',
  variableLogic: 'and',
  itemSpec: '',
  replacementLines: ['', '', '', ''],
});

export const emptyServerPage = (): ServerPageSettings => ({
  operationOnly: false,
  displayCondition: emptyCondition(),
  flow: {
    conditionalTiming: 'after',
    ending: false,
    condition: emptyCondition(),
  },
  effects: {
    giveItems: '',
    takeItems: '',
    variablesSet: '',
    variablesDelete: '',
    chatInputVariable: '',
    sounds: '',
    message: '',
    messageColor: '#FFFFFF',
    returnTarget: '',
    serverCommand: '',
    commandTarget: 'player',
  },
});

export const emptyChoiceSettings = (): ServerChoiceSettings => ({ condition: emptyCondition() });
