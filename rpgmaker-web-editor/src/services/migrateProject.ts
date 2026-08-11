import type { DialogueChoice, DialoguePage, PageAppearance, RPGProject } from '../domain/project';
import { emptyCondition, emptyServerPage } from '../domain/serverSettings';
import type { ServerCondition, ServerPageSettings } from '../domain/serverSettings';

const characterIds: Record<string, string> = {
  merchant: 'INNKEEPER',
  villager: 'VILLAGER',
  warrior: 'WARRIOR',
  mage: 'MAGE',
  guard: 'SENTINEL',
  king: 'KING',
};

const expressions: Record<string, string> = {
  기본: 'NEUTRAL',
  무표정: 'NEUTRAL',
  기쁨: 'HAPPY',
  슬픔: 'SAD',
  화남: 'ANGRY',
  놀람: 'SURPRISED',
  당황: 'SURPRISED',
  부끄러움: 'EMBARRASSED',
};

type ExtendedPage = DialoguePage & { server?: ReturnType<typeof emptyServerPage> };

function lines(value?: readonly string[]): [string, string, string, string] {
  return [value?.[0] ?? '', value?.[1] ?? '', value?.[2] ?? '', value?.[3] ?? ''];
}

function condition(value?: Partial<ServerCondition>): ServerCondition {
  return { ...emptyCondition(), ...value, replacementLines: lines(value?.replacementLines) };
}

function serverPage(value?: Partial<ServerPageSettings>): ServerPageSettings {
  const fallback = emptyServerPage();
  return {
    ...fallback,
    ...value,
    displayCondition: condition(value?.displayCondition),
    flow: { ...fallback.flow, ...value?.flow, condition: condition(value?.flow?.condition) },
    effects: { ...fallback.effects, ...value?.effects },
  };
}

function appearance(value?: Partial<PageAppearance>): PageAppearance {
  const visible = value?.visible ?? true;
  const next = { visible, inheritPrevious: false, ...value, speakerVisible: value?.speakerVisible ?? visible };
  next.characterId = characterIds[next.characterId ?? ''] ?? next.characterId?.toUpperCase();
  next.expression = expressions[next.expression ?? ''] ?? next.expression?.toUpperCase() ?? 'NEUTRAL';
  return next;
}

function migrateChoice(rawChoice: DialogueChoice) {
  const choice = rawChoice;
  choice.server = { condition: condition(choice.server?.condition) };
  choice.responsePages ??= [];
  for (const response of choice.responsePages) {
    response.lines = lines(response.lines);
    response.appearance = appearance(response.appearance);
    response.choices ??= [];
    response.server = serverPage(response.server);
    response.choices.forEach(migrateChoice);
  }
}

export function migrateProject(project: RPGProject): RPGProject {
  const next = structuredClone(project);
  for (const dialogue of next.dialogues) {
    for (const rawPage of dialogue.pages) {
      const page = rawPage as ExtendedPage;
      page.lines = lines(page.lines);
      page.appearance = appearance(page.appearance);
      page.server = serverPage(page.server);
      if (page.operationOnly) page.server.operationOnly = true;
      if (page.flow?.ending) page.server.flow.ending = true;
      page.choices ??= [];
      page.choices.forEach(migrateChoice);
    }
  }
  next.pendingServerDeletes ??= [];
  next.schemaVersion = 3;
  next.characters = [];
  return next;
}
