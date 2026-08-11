import type { Dialogue, DialoguePage, RPGProject } from '../domain/project';
import { emptyServerPage } from '../domain/serverSettings';

export const createId = () => crypto.randomUUID();

export function createPage(label?: string): DialoguePage {
  return {
    id: createId(),
    editorLabel: label,
    speaker: '',
    lines: ['', '', '', ''],
    appearance: {
      visible: true,
      speakerVisible: true,
      inheritPrevious: false,
      expression: 'NEUTRAL',
    },
    choices: [],
    flow: { ending: false },
    effects: [],
    server: emptyServerPage(),
  };
}

export function createDialogue(name = '새 대화'): Dialogue {
  const page = createPage('첫 페이지');
  return {
    id: createId(),
    name,
    pages: [page],
    startPageId: page.id,
  };
}

export function createProject(name: string): RPGProject {
  const now = new Date().toISOString();
  const dialogue = createDialogue('첫 대화');
  return {
    id: createId(),
    name,
    dialogues: [dialogue],
    characters: [],
    variables: [],
    items: [],
    createdAt: now,
    updatedAt: now,
    schemaVersion: 3,
  };
}

export function createDemoProject(): RPGProject {
  const project = createProject('튜토리얼 RPG');
  const dialogue = project.dialogues[0];
  dialogue.name = '마을의 상인';

  const offer = dialogue.pages[0];
  offer.editorLabel = '물건 제안';
  offer.speaker = '상인';
  offer.lines = ['좋은 물건이 있어.', '한번 보고 갈래?', '', ''];
  offer.appearance.characterId = 'VILLAGER';
  offer.appearance.gender = 'male';
  offer.appearance.expression = 'NEUTRAL';

  const buy = createPage('구매');
  buy.speaker = '상인';
  buy.lines = ['현명한 선택이야.', '', '', ''];
  buy.appearance.characterId = 'VILLAGER';
  buy.appearance.gender = 'male';
  buy.appearance.expression = 'NEUTRAL';
  buy.flow.ending = true;

  const decline = createPage('거절');
  decline.speaker = '상인';
  decline.lines = ['다음에 또 보자.', '', '', ''];
  decline.appearance.characterId = 'VILLAGER';
  decline.appearance.gender = 'male';
  decline.appearance.expression = 'NEUTRAL';
  decline.flow.ending = true;

  offer.choices = [
    { id: createId(), label: '보여줘', targetPageId: buy.id },
    { id: createId(), label: '관심 없어', targetPageId: decline.id },
  ];
  dialogue.pages.push(buy, decline);
  return project;
}
