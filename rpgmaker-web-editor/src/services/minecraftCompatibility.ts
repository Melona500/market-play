import type {
  Dialogue,
  DialogueChoice,
  DialogueChoiceResponsePage,
  DialoguePage,
  PageAppearance,
} from '../domain/project';
import { emptyChoiceSettings, emptyCondition, emptyServerPage } from '../domain/serverSettings';
import type { ServerCondition, ServerEffects } from '../domain/serverSettings';
import {
  findCharacterByPortrait,
  getCharacter,
  normalizedGender,
  resolvePortraitId,
  type CharacterManifest,
} from './characterRegistry';

type JsonMap = Record<string, unknown>;

const map = (value: unknown): JsonMap =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as JsonMap) : {};
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const text = (value: unknown, fallback = '') => (value == null ? fallback : String(value));
const number = (value: unknown, fallback = 0) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};
const bool = (value: unknown, fallback = false) =>
  typeof value === 'boolean' ? value : value == null ? fallback : String(value).toLowerCase() === 'true';

function cleanRecord(value: JsonMap): JsonMap {
  return Object.fromEntries(
    Object.entries(value).filter(([, child]) => {
      if (child === undefined || child === null || child === '') return false;
      if (Array.isArray(child)) return child.length > 0;
      if (typeof child === 'object') return Object.keys(child as JsonMap).length > 0;
      return true;
    }),
  );
}

function conditionToServer(condition: ServerCondition): JsonMap {
  if (condition.mode === 'none') return { type: 'NONE' };
  const result: JsonMap = {
    type: condition.mode.toUpperCase(),
    variable: condition.variable,
    operator: condition.operator.replace('-', '_').toUpperCase(),
    value: condition.value,
    'extra-variables': condition.extraVariables,
    'variable-logic': condition.variableLogic.toUpperCase(),
    'item-spec': condition.itemSpec,
  };
  const replacement = condition.replacementLines.join('\n').replace(/\n+$/g, '');
  if (replacement) result.replacement = replacement;
  return cleanRecord(result);
}

function conditionFromServer(raw: unknown): ServerCondition {
  const data = map(raw);
  const condition = emptyCondition();
  const type = text(data.type, 'NONE').toLowerCase();
  condition.mode = ['variable', 'item', 'both', 'any'].includes(type)
    ? (type as ServerCondition['mode'])
    : 'none';
  condition.variable = text(data.variable);
  condition.value = text(data.value);
  const operator = text(data.operator, 'EQ').toLowerCase().replace('_', '-');
  condition.operator = [
    'eq',
    'ne',
    'gt',
    'gte',
    'lt',
    'lte',
    'is-set',
    'is-unset',
  ].includes(operator)
    ? (operator as ServerCondition['operator'])
    : 'eq';
  condition.extraVariables = text(data['extra-variables']);
  const logic = text(data['variable-logic'], 'AND').toLowerCase();
  condition.variableLogic = ['and', 'or', 'xor', 'not'].includes(logic)
    ? (logic as ServerCondition['variableLogic'])
    : 'and';
  condition.itemSpec = text(data['item-spec']);
  const replacement = text(data.replacement);
  condition.replacementLines = [
    replacement.split('\n')[0] ?? '',
    replacement.split('\n')[1] ?? '',
    replacement.split('\n')[2] ?? '',
    replacement.split('\n')[3] ?? '',
  ];
  return condition;
}

function effectsToServer(effects: ServerEffects): JsonMap {
  return cleanRecord({
    items: effects.giveItems,
    'take-items': effects.takeItems,
    'variables-set': effects.variablesSet,
    'variables-delete': effects.variablesDelete,
    'chat-input-variable': effects.chatInputVariable,
    sounds: effects.sounds,
    message: effects.message,
    'message-color': effects.messageColor || '#FFFFFF',
    'return-mode': effects.returnTarget ? 'TARGET' : 'NONE',
    'return-target': effects.returnTarget,
    command: effects.serverCommand,
    'command-target': effects.commandTarget.toUpperCase(),
  });
}

function effectsFromServer(raw: unknown): ServerEffects {
  const data = map(raw);
  const result = emptyServerPage().effects;
  result.giveItems = text(data.items || data.item);
  result.takeItems = text(data['take-items'] || data['take-item']);
  result.variablesSet = text(data['variables-set']);
  if (!result.variablesSet && data.variable) {
    const action = text(data['variable-action'], 'SET').toLowerCase();
    const operator = action === 'add' ? '+=' : action === 'subtract' ? '-=' : '=';
    result.variablesSet = `${text(data.variable)}${operator}${text(data.value)}`;
  }
  result.variablesDelete = text(data['variables-delete']);
  if (!result.variablesDelete && text(data['variable-action']).toUpperCase() === 'DELETE')
    result.variablesDelete = text(data.variable);
  result.chatInputVariable = text(data['chat-input-variable']);
  result.sounds = text(data.sounds);
  if (!result.sounds && data.sound) {
    result.sounds = `${text(data.sound)}:${text(data['sound-pitch'], '1')}:${text(data['sound-volume'], '1')}:1`;
  }
  result.message = text(data.message);
  result.messageColor = text(data['message-color'], '#FFFFFF');
  result.returnTarget = text(data['return-target']);
  result.serverCommand = text(data.command);
  const target = text(data['command-target'], 'PLAYER').toLowerCase();
  result.commandTarget = ['player', 'all', 'nearest'].includes(target)
    ? (target as ServerEffects['commandTarget'])
    : 'player';
  return result;
}

function pageText(lines: [string, string, string, string]) {
  const copy = [...lines];
  while (copy.length > 1 && copy.at(-1) === '') copy.pop();
  return copy.join('\n');
}

function appearanceToServer(appearance: PageAppearance, manifest: CharacterManifest) {
  const character = getCharacter(manifest, appearance.characterId);
  if (!character) return { portrait: '', expression: '' };
  const gender = normalizedGender(character, appearance.gender);
  return {
    portrait: resolvePortraitId(character, gender),
    expression: appearance.expression ?? 'NEUTRAL',
  };
}

function responsePageFromServer(
  message: string,
  portrait: string,
  expression: string,
  portraitVisible: boolean,
  speakerVisible: boolean,
  rawEffects: unknown,
  rawChoices: unknown,
  pageIds: string[],
  manifest: CharacterManifest,
): DialogueChoiceResponsePage {
  const split = message.split('\n');
  const found = findCharacterByPortrait(manifest, portrait);
  const server = emptyServerPage();
  server.effects = effectsFromServer(rawEffects);
  return {
    id: crypto.randomUUID(),
    lines: [split[0] ?? '', split[1] ?? '', split[2] ?? '', split[3] ?? ''],
    appearance: {
      visible: portraitVisible,
      speakerVisible,
      inheritPrevious: false,
      characterId: found?.character.id,
      gender: found?.gender === 'FEMALE' ? 'female' : found?.gender === 'MALE' ? 'male' : undefined,
      expression: expression || 'NEUTRAL',
    },
    choices: choicesFromServer(rawChoices, pageIds, manifest),
    server,
  };
}

function choicesToServer(
  choices: DialogueChoice[],
  pageIndex: Map<string, number>,
  manifest: CharacterManifest,
): JsonMap {
  const output: JsonMap = { 'choice-count': choices.length };
  choices.slice(0, 8).forEach((choice, choiceIndex) => {
    const slot = choiceIndex + 1;
    output[`choice-${slot}`] = choice.label;
    output[`target-page-${slot}`] = choice.targetPageId ? pageIndex.get(choice.targetPageId) ?? 0 : 0;
    if (choice.targetDialogueName) output[`target-dialogue-${slot}`] = choice.targetDialogueName;
    output[`end-${slot}`] = choice.endAfterTarget ?? false;
    if (choice.speakerOverride) output[`speaker-${slot}`] = choice.speakerOverride;
    if (choice.server?.condition && choice.server.condition.mode !== 'none')
      output[`condition-${slot}`] = conditionToServer(choice.server.condition);

    const responsePages = choice.responsePages ?? [];
    if (!responsePages.length) return;
    output[`response-pages-${slot}`] = responsePages.map((response) => pageText(response.lines));

    const portraits: JsonMap = {};
    const expressions: JsonMap = {};
    const portraitVisibility: JsonMap = {};
    const speakerVisibility: JsonMap = {};
    const effects: JsonMap = {};
    const nestedChoices: JsonMap = {};
    responsePages.forEach((response, responseIndex) => {
      const appearance = appearanceToServer(response.appearance, manifest);
      portraitVisibility[responseIndex] = response.appearance.visible;
      speakerVisibility[responseIndex] = response.appearance.speakerVisible;
      if (appearance.portrait) portraits[responseIndex] = appearance.portrait;
      if (appearance.expression) expressions[responseIndex] = appearance.expression;
      const responseEffects = effectsToServer((response.server ?? emptyServerPage()).effects);
      if (Object.keys(responseEffects).length) effects[responseIndex] = responseEffects;
      if (response.choices.length)
        nestedChoices[responseIndex] = choicesToServer(response.choices, pageIndex, manifest);
    });
    if (Object.keys(portraits).length) output[`response-portrait-${slot}`] = portraits;
    if (Object.keys(expressions).length) output[`response-expression-${slot}`] = expressions;
    output[`response-show-portraits-${slot}`] = portraitVisibility;
    output[`response-show-speakers-${slot}`] = speakerVisibility;
    if (Object.keys(effects).length) output[`response-effects-${slot}`] = effects;
    if (Object.keys(nestedChoices).length) output[`response-page-choices-${slot}`] = nestedChoices;
  });
  return output;
}

function choicesFromServer(raw: unknown, pageIds: string[], manifest: CharacterManifest): DialogueChoice[] {
  const data = map(raw);
  const count = Math.min(8, number(data['choice-count']));
  return Array.from({ length: count }, (_, choiceIndex) => {
    const slot = choiceIndex + 1;
    const targetNumber = number(data[`target-page-${slot}`]);
    const responseMessages = list(data[`response-pages-${slot}`]).map((value) => text(value));
    if (!responseMessages.length && data[`response-${slot}`] != null)
      responseMessages.push(text(data[`response-${slot}`]));
    const portraits = map(data[`response-portrait-${slot}`]);
    const expressions = map(data[`response-expression-${slot}`]);
    const portraitVisibility = map(data[`response-show-portraits-${slot}`]);
    const speakerVisibility = map(data[`response-show-speakers-${slot}`]);
    const effects = map(data[`response-effects-${slot}`]);
    const nested = map(data[`response-page-choices-${slot}`]);
    const responsePages = responseMessages.map((message, responseIndex) =>
      responsePageFromServer(
        message,
        text(portraits[responseIndex]),
        text(expressions[responseIndex], 'NEUTRAL'),
        bool(portraitVisibility[responseIndex], true),
        bool(speakerVisibility[responseIndex], bool(portraitVisibility[responseIndex], true)),
        effects[responseIndex] ?? (responseIndex === 0 ? data[`effect-${slot}`] : undefined),
        nested[responseIndex],
        pageIds,
        manifest,
      ),
    );
    return {
      id: crypto.randomUUID(),
      label: text(data[`choice-${slot}`]),
      targetPageId: targetNumber > 0 ? pageIds[targetNumber - 1] : undefined,
      targetDialogueName: text(data[`target-dialogue-${slot}`]) || undefined,
      endAfterTarget: bool(data[`end-${slot}`]),
      speakerOverride: text(data[`speaker-${slot}`]) || undefined,
      responsePages,
      server: { condition: conditionFromServer(data[`condition-${slot}`]) },
    };
  });
}

export function exportMinecraftDialogue(dialogue: Dialogue, manifest: CharacterManifest): JsonMap {
  const output: JsonMap = structuredClone(dialogue.server?.raw ?? {});
  const pageIndex = new Map(dialogue.pages.map((page, index) => [page.id, index + 1]));
  output.title = dialogue.name;
  output['message-pages'] = dialogue.pages.map((page) => pageText(page.lines));
  output.speaker = dialogue.pages[0]?.speaker ?? '';
  if (dialogue.nextDialogueName) output['next-dialogue'] = dialogue.nextDialogueName;
  else delete output['next-dialogue'];

  const speakers: JsonMap = {};
  const portraits: JsonMap = {};
  const expressions: JsonMap = {};
  const visible: JsonMap = {};
  const speakersVisible: JsonMap = {};
  const choices: JsonMap = {};
  const conditions: JsonMap = {};
  const effects: JsonMap = {};
  const flows: JsonMap = {};
  const operations: JsonMap = {};

  dialogue.pages.forEach((page, index) => {
    const server = page.server ?? emptyServerPage();
    speakers[index] = page.speaker;
    visible[index] = page.appearance.visible;
    speakersVisible[index] = page.appearance.speakerVisible;

    const appearance = appearanceToServer(page.appearance, manifest);
    if (appearance.portrait) portraits[index] = appearance.portrait;
    if (appearance.expression) expressions[index] = appearance.expression;

    if (page.choices.length) choices[index] = choicesToServer(page.choices, pageIndex, manifest);

    if (server.displayCondition.mode !== 'none') conditions[index] = conditionToServer(server.displayCondition);
    const pageEffects = effectsToServer(server.effects);
    if (Object.keys(pageEffects).length) effects[index] = pageEffects;

    const flow: JsonMap = {
      'next-page': server.flow.nextPageId ? pageIndex.get(server.flow.nextPageId) ?? 0 : 0,
      terminal: server.flow.ending,
      'jump-target': server.flow.conditionalTargetPageId
        ? pageIndex.get(server.flow.conditionalTargetPageId) ?? 0
        : 0,
      'jump-timing': server.flow.conditionalTiming.toUpperCase(),
    };
    if (server.flow.condition.mode !== 'none') flow.condition = conditionToServer(server.flow.condition);
    flows[index] = cleanRecord(flow);
    if (server.operationOnly) operations[index] = true;
  });

  output['page-speakers'] = speakers;
  output['page-portraits'] = portraits;
  output['page-expressions'] = expressions;
  output['page-show-portraits'] = visible;
  output['page-show-speakers'] = speakersVisible;
  output['page-choices'] = choices;
  output['page-conditions'] = conditions;
  output['page-effects'] = effects;
  output['page-flow'] = flows;
  output['page-operation-only'] = operations;
  return output;
}

export function importMinecraftDialogue(
  remoteName: string,
  raw: JsonMap,
  revision: string,
  ownerUuid: string,
  manifest: CharacterManifest,
): Dialogue {
  const pageTexts = list(raw['message-pages']).map((value) => text(value));
  if (!pageTexts.length) pageTexts.push(text(raw.message));
  const pageIds = pageTexts.map(() => crypto.randomUUID());
  const speakerMap = map(raw['page-speakers']);
  const portraitMap = map(raw['page-portraits']);
  const expressionMap = map(raw['page-expressions']);
  const visibleMap = map(raw['page-show-portraits']);
  const speakerVisibleMap = map(raw['page-show-speakers']);
  const choiceMap = map(raw['page-choices']);
  const conditionMap = map(raw['page-conditions']);
  const effectMap = map(raw['page-effects']);
  const flowMap = map(raw['page-flow']);
  const operationMap = map(raw['page-operation-only']);
  const defaultSpeaker = text(raw.speaker);
  let inheritedPortrait = text(raw.portrait);
  let inheritedExpression = text(raw.expression, 'NEUTRAL');

  const pages: DialoguePage[] = pageTexts.map((message, index) => {
    inheritedPortrait = text(portraitMap[index], inheritedPortrait);
    inheritedExpression = text(expressionMap[index], inheritedExpression);
    const found = findCharacterByPortrait(manifest, inheritedPortrait);
    const split = message.split('\n');
    const server = emptyServerPage();
    server.displayCondition = conditionFromServer(conditionMap[index]);
    server.effects = effectsFromServer(effectMap[index]);
    server.operationOnly = bool(operationMap[index]);

    const serverFlow = map(flowMap[index]);
    server.flow.ending = bool(serverFlow.terminal);
    server.flow.conditionalTiming =
      text(serverFlow['jump-timing'], 'AFTER').toUpperCase() === 'BEFORE' ? 'before' : 'after';
    server.flow.condition = conditionFromServer(serverFlow.condition);

    return {
      id: pageIds[index],
      editorLabel: `Page ${index + 1}`,
      speaker: text(speakerMap[index], defaultSpeaker),
      lines: [split[0] ?? '', split[1] ?? '', split[2] ?? '', split[3] ?? ''],
      appearance: {
        visible: bool(visibleMap[index], true),
        speakerVisible: bool(speakerVisibleMap[index], bool(visibleMap[index], true)),
        inheritPrevious: portraitMap[index] == null && index > 0,
        characterId: found?.character.id,
        gender: found?.gender === 'FEMALE' ? 'female' : found?.gender === 'MALE' ? 'male' : undefined,
        expression: inheritedExpression || 'NEUTRAL',
      },
      choices: choicesFromServer(choiceMap[index], pageIds, manifest),
      flow: { ending: server.flow.ending },
      effects: [],
      operationOnly: server.operationOnly,
      server,
    };
  });

  pages.forEach((page, index) => {
    const serverFlow = map(flowMap[index]);
    const next = number(serverFlow['next-page']);
    const jump = number(serverFlow['jump-target']);
    if (next > 0) page.server!.flow.nextPageId = pageIds[next - 1];
    if (jump > 0) page.server!.flow.conditionalTargetPageId = pageIds[jump - 1];
  });

  return {
    id: crypto.randomUUID(),
    name: text(raw.title, remoteName),
    pages,
    startPageId: pages[0]?.id ?? '',
    nextDialogueName: text(raw['next-dialogue']) || undefined,
    server: {
      ownerUuid,
      remoteName,
      revision,
      raw: structuredClone(raw),
      lastSyncedAt: new Date().toISOString(),
    },
  };
}
