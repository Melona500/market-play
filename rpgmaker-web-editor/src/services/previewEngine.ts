import type { Dialogue, DialogueChoice, DialogueChoiceResponsePage, DialoguePage } from '../domain/project';
import { emptyServerPage } from '../domain/serverSettings';
import type { ServerCondition, ServerPageSettings } from '../domain/serverSettings';

type PageWithServer = DialoguePage & { server?: ServerPageSettings };
export type PreviewPage = PageWithServer | (DialogueChoiceResponsePage & { server?: ServerPageSettings });
type ChoiceWithServer = DialogueChoice & { server?: { condition: ServerCondition } };

interface PreviewResponseFrame {
  pageIds: string[];
  index: number;
  resumePageId?: string;
  endAfter: boolean;
}

export interface PreviewState {
  currentPageId: string;
  variables: Record<string, string | number | boolean>;
  items: Record<string, number>;
  effects: string[];
  flow: string[];
  ended: boolean;
  endAfterCurrent: boolean;
  responseFrames: PreviewResponseFrame[];
  speaker: string;
  safetyError?: string;
}

const entries = (value: string) =>
  value
    .split(/[,\n]/)
    .map((entry) => entry.trim())
    .filter(Boolean);

const asNumber = (value: unknown) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
};

function compare(actual: unknown, operator: ServerCondition['operator'], expected: string) {
  if (operator === 'is-set') return actual !== undefined && actual !== null && actual !== '';
  if (operator === 'is-unset') return actual === undefined || actual === null || actual === '';
  const actualNumber = asNumber(actual);
  const expectedNumber = asNumber(expected);
  if (actualNumber !== undefined && expectedNumber !== undefined) {
    if (operator === 'eq') return actualNumber === expectedNumber;
    if (operator === 'ne') return actualNumber !== expectedNumber;
    if (operator === 'gt') return actualNumber > expectedNumber;
    if (operator === 'gte') return actualNumber >= expectedNumber;
    if (operator === 'lt') return actualNumber < expectedNumber;
    if (operator === 'lte') return actualNumber <= expectedNumber;
  }
  const left = String(actual ?? '');
  if (operator === 'eq') return left === expected;
  if (operator === 'ne') return left !== expected;
  if (operator === 'gt') return left > expected;
  if (operator === 'gte') return left >= expected;
  if (operator === 'lt') return left < expected;
  if (operator === 'lte') return left <= expected;
  return false;
}

function extraVariableResult(expression: string, variables: PreviewState['variables']) {
  const match = expression.match(/^(.+?)\s*(==|=|!=|>=|<=|>|<)\s*(.*)$/u);
  if (!match) return false;
  const [, name, operator, value] = match;
  const mapped: ServerCondition['operator'] =
    operator === '!=' ? 'ne' : operator === '>' ? 'gt' : operator === '>=' ? 'gte' : operator === '<' ? 'lt' : operator === '<=' ? 'lte' : 'eq';
  return compare(variables[name], mapped, value);
}

function itemParts(spec: string) {
  const value = spec.trim();
  if (!value) return { id: '', amount: 1 };
  if (value.startsWith('@')) {
    const match = value.match(/^(@[^:]+)(?::(\d+))?/);
    return { id: match?.[1] ?? value, amount: Number(match?.[2] ?? 1) };
  }
  const parts = value.split(':');
  const id = parts.length >= 2 ? `${parts[0]}:${parts[1]}` : value;
  return { id, amount: Number(parts[2] ?? 1) || 1 };
}

export function evaluateCondition(condition: ServerCondition, state: PreviewState) {
  if (condition.mode === 'none') return true;
  const variableChecks: boolean[] = [];
  if (condition.variable) variableChecks.push(compare(state.variables[condition.variable], condition.operator, condition.value));
  entries(condition.extraVariables).forEach((entry) => variableChecks.push(extraVariableResult(entry, state.variables)));

  let variableResult = variableChecks.length === 0;
  if (condition.variableLogic === 'and') variableResult = variableChecks.every(Boolean);
  if (condition.variableLogic === 'or') variableResult = variableChecks.some(Boolean);
  if (condition.variableLogic === 'xor') variableResult = variableChecks.filter(Boolean).length === 1;
  if (condition.variableLogic === 'not') variableResult = !variableChecks[0];

  const required = itemParts(condition.itemSpec);
  const itemResult = !required.id || (state.items[required.id] ?? 0) >= required.amount;

  if (condition.mode === 'variable') return variableResult;
  if (condition.mode === 'item') return itemResult;
  if (condition.mode === 'both') return variableResult && itemResult;
  if (condition.mode === 'any') return variableResult || itemResult;
  return true;
}

export function conditionSummary(condition: ServerCondition) {
  if (condition.mode === 'none') return '조건이 없습니다. 항상 적용됩니다.';
  const operator = {
    eq: '같을 때',
    ne: '다를 때',
    gt: '보다 클 때',
    gte: '이상일 때',
    lt: '보다 작을 때',
    lte: '이하일 때',
    'is-set': '설정되어 있을 때',
    'is-unset': '설정되어 있지 않을 때',
  }[condition.operator];
  const variable = condition.variable
    ? `${condition.variable}${condition.operator.startsWith('is-') ? '이 ' : ` 값이 ${condition.value}와 `}${operator}`
    : '';
  const item = condition.itemSpec ? `${condition.itemSpec} 아이템 조건을 만족할 때` : '';
  if (condition.mode === 'both') return [variable, item].filter(Boolean).join(' 그리고 ');
  if (condition.mode === 'any') return [variable, item].filter(Boolean).join(' 또는 ');
  return variable || item || '조건 설정이 필요합니다.';
}

function updateVariable(state: PreviewState, operation: string) {
  const random = operation.match(/^(.+?)\s*=\s*random\(\s*(-?\d+)\s*\.\.\s*(-?\d+)\s*\)$/u);
  if (random) {
    const minimum = Math.min(Number(random[2]), Number(random[3]));
    const maximum = Math.max(Number(random[2]), Number(random[3]));
    state.variables[random[1]] = Math.floor(Math.random() * (maximum - minimum + 1)) + minimum;
    state.effects.push(`난수 변수: ${operation}`);
    return;
  }
  const match = operation.match(/^(.+?)\s*(=|\+=|-=|\*=|\/=)\s*(.*)$/u);
  if (!match) return;
  const [, name, operator, raw] = match;
  const numeric = asNumber(raw);
  const value: string | number | boolean = raw === 'true' ? true : raw === 'false' ? false : numeric ?? raw;
  if (operator === '=') state.variables[name] = value;
  else {
    const current = asNumber(state.variables[name]) ?? 0;
    const operand = asNumber(value) ?? 0;
    if (operator === '+=') state.variables[name] = current + operand;
    if (operator === '-=') state.variables[name] = current - operand;
    if (operator === '*=') state.variables[name] = current * operand;
    if (operator === '/=') state.variables[name] = operand === 0 ? current : current / operand;
  }
  state.effects.push(`변수: ${operation}`);
}

function updateItem(state: PreviewState, spec: string, direction: 1 | -1) {
  const item = itemParts(spec);
  if (!item.id) return;
  state.items[item.id] = Math.max(0, (state.items[item.id] ?? 0) + item.amount * direction);
  state.effects.push(`${direction > 0 ? '아이템 지급' : '아이템 회수'}: ${item.id} × ${item.amount}`);
}

function returnPageId(dialogue: Dialogue, target: string) {
  const match = target.match(/^(?:PAGE|CHOICE):p(\d+)(?:#c\d+)?$/i);
  return match ? dialogue.pages[Number(match[1])]?.id : undefined;
}

function applyEffects(dialogue: Dialogue, page: PreviewPage, state: PreviewState) {
  const effects = (page.server ?? emptyServerPage()).effects;
  entries(effects.giveItems).forEach((item) => updateItem(state, item, 1));
  entries(effects.takeItems).forEach((item) => updateItem(state, item, -1));
  entries(effects.variablesSet).forEach((operation) => updateVariable(state, operation));
  entries(effects.variablesDelete).forEach((name) => {
    delete state.variables[name];
    state.effects.push(`변수 삭제: ${name}`);
  });
  if (effects.chatInputVariable) state.effects.push(`채팅 입력 대기 변수: ${effects.chatInputVariable}`);
  entries(effects.sounds).forEach((sound) => state.effects.push(`사운드: ${sound}`));
  if (effects.message) state.effects.push(`메시지: ${effects.message}`);
  if (effects.serverCommand) state.effects.push(`[실행하지 않음] 서버 명령: ${effects.serverCommand}`);
  if (effects.returnTarget) {
    state.effects.push(`Return: ${effects.returnTarget}`);
    return returnPageId(dialogue, effects.returnTarget);
  }
  return undefined;
}

function responsePageById(choices: DialogueChoice[], id: string): PreviewPage | undefined {
  for (const choice of choices) {
    for (const response of choice.responsePages ?? []) {
      if (response.id === id) return response;
      const nested = responsePageById(response.choices, id);
      if (nested) return nested;
    }
  }
  return undefined;
}

export function findPreviewPage(dialogue: Dialogue, id: string): PreviewPage | undefined {
  return dialogue.pages.find((page) => page.id === id) as PageWithServer | undefined
    ?? dialogue.pages.map((page) => responsePageById(page.choices, id)).find(Boolean);
}

function mainPage(dialogue: Dialogue, page: PreviewPage): page is PageWithServer {
  return dialogue.pages.some((candidate) => candidate.id === page.id);
}

function nextPageId(dialogue: Dialogue, page: PageWithServer, state: PreviewState) {
  const server = page.server ?? emptyServerPage();
  if (server.flow.ending) return undefined;
  if (
    server.flow.conditionalTiming === 'after' &&
    server.flow.conditionalTargetPageId &&
    evaluateCondition(server.flow.condition, state)
  ) {
    return server.flow.conditionalTargetPageId;
  }
  if (server.flow.nextPageId) return server.flow.nextPageId;
  const index = dialogue.pages.findIndex((candidate) => candidate.id === page.id);
  return dialogue.pages[index + 1]?.id;
}

function advanceResponse(state: PreviewState) {
  while (state.responseFrames.length > 0) {
    const frame = state.responseFrames.at(-1)!;
    frame.index += 1;
    if (frame.index < frame.pageIds.length) {
      state.currentPageId = frame.pageIds[frame.index];
      return true;
    }
    state.responseFrames.pop();
    if (frame.endAfter) {
      state.ended = true;
      return false;
    }
    if (frame.resumePageId) {
      state.responseFrames = [];
      state.currentPageId = frame.resumePageId;
      return true;
    }
  }
  state.ended = true;
  return false;
}

function advanceAfterPage(dialogue: Dialogue, page: PreviewPage, state: PreviewState) {
  const frame = state.responseFrames.at(-1);
  if (frame?.pageIds[frame.index] === page.id) return advanceResponse(state);
  if (!mainPage(dialogue, page)) return false;
  const target = nextPageId(dialogue, page, state);
  if (!target) return false;
  state.currentPageId = target;
  return true;
}

function enterPage(dialogue: Dialogue, state: PreviewState) {
  let hops = 0;
  while (!state.ended && hops++ < 64) {
    const page = findPreviewPage(dialogue, state.currentPageId);
    if (!page) {
      state.ended = true;
      state.safetyError = '대상 페이지를 찾을 수 없습니다.';
      return state;
    }
    state.flow.push('editorLabel' in page && page.editorLabel ? page.editorLabel : `Response ${page.id}`);
    const server = page.server ?? emptyServerPage();
    if (mainPage(dialogue, page)) state.speaker = page.speaker;
    if (
      server.flow.conditionalTiming === 'before' &&
      server.flow.conditionalTargetPageId &&
      evaluateCondition(server.flow.condition, state)
    ) {
      state.currentPageId = server.flow.conditionalTargetPageId;
      continue;
    }
    if (server.operationOnly) {
      const returned = applyEffects(dialogue, page, state);
      if (returned) {
        state.responseFrames = [];
        state.currentPageId = returned;
        continue;
      }
      if (!advanceAfterPage(dialogue, page, state)) {
        state.ended = true;
        return state;
      }
      continue;
    }
    return state;
  }
  if (hops >= 64) {
    state.ended = true;
    state.safetyError = 'Flow safety: 자동 이동이 64회를 초과했습니다.';
  }
  return state;
}

export function createPreviewState(
  dialogue: Dialogue,
  variables: PreviewState['variables'] = {},
  items: PreviewState['items'] = {},
): PreviewState {
  return enterPage(dialogue, {
    currentPageId: dialogue.startPageId || dialogue.pages[0]?.id || '',
    variables: structuredClone(variables),
    items: structuredClone(items),
    effects: [],
    flow: [],
    ended: dialogue.pages.length === 0,
    endAfterCurrent: false,
    responseFrames: [],
    speaker: dialogue.pages[0]?.speaker ?? '',
  });
}

export function advancePreview(dialogue: Dialogue, current: PreviewState): PreviewState {
  const state = structuredClone(current);
  if (state.ended) return state;
  const page = findPreviewPage(dialogue, state.currentPageId);
  if (!page) return { ...state, ended: true, safetyError: '현재 페이지가 삭제되었습니다.' };
  const returned = applyEffects(dialogue, page, state);
  if (returned) {
    state.responseFrames = [];
    state.currentPageId = returned;
    state.endAfterCurrent = false;
    return enterPage(dialogue, state);
  }
  if (state.endAfterCurrent) return { ...state, ended: true };
  if (!advanceAfterPage(dialogue, page, state)) return { ...state, ended: true };
  return enterPage(dialogue, state);
}

export function visibleChoices(page: Pick<DialoguePage, 'choices'>, state: PreviewState) {
  return page.choices.filter((choice) =>
    evaluateCondition((choice as ChoiceWithServer).server?.condition ?? { ...emptyServerPage().displayCondition }, state),
  );
}

export function choosePreview(dialogue: Dialogue, current: PreviewState, choice: DialogueChoice): PreviewState {
  const state = structuredClone(current);
  if (state.ended) return state;
  const page = findPreviewPage(dialogue, state.currentPageId);
  if (!page) return { ...state, ended: true };
  const returned = applyEffects(dialogue, page, state);
  if (returned) {
    state.responseFrames = [];
    state.currentPageId = returned;
    return enterPage(dialogue, state);
  }

  const responses = choice.responsePages ?? [];
  if (responses.length > 0) {
    const resumePageId = choice.targetPageId ?? (mainPage(dialogue, page) ? nextPageId(dialogue, page, state) : undefined);
    state.responseFrames.push({
      pageIds: responses.map((response) => response.id),
      index: 0,
      resumePageId,
      endAfter: choice.endAfterTarget ?? false,
    });
    state.currentPageId = responses[0].id;
    if (choice.speakerOverride) state.speaker = choice.speakerOverride;
    state.endAfterCurrent = false;
    return enterPage(dialogue, state);
  }

  if (choice.targetPageId) {
    state.currentPageId = choice.targetPageId;
    state.endAfterCurrent = choice.endAfterTarget ?? false;
    return enterPage(dialogue, state);
  }

  if (choice.endAfterTarget) return { ...state, ended: true };
  if (!advanceAfterPage(dialogue, page, state)) return { ...state, ended: true };
  state.endAfterCurrent = false;
  return enterPage(dialogue, state);
}
