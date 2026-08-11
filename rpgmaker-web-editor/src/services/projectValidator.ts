import type { Dialogue, DialogueChoice, DialoguePage, RPGProject } from '../domain/project';
import { emptyServerPage } from '../domain/serverSettings';
import type { ServerCondition, ServerPageSettings } from '../domain/serverSettings';
import {
  availableExpressions,
  getCharacter,
  normalizedGender,
  type CharacterManifest,
} from './characterRegistry';
import { visibleLength } from './dialogueText';

export { visibleLength } from './dialogueText';

export type ValidationSection = 'script' | 'character' | 'choices' | 'condition' | 'effects' | 'flow' | 'other';

export interface ValidationIssue {
  id: string;
  severity: 'error' | 'warning';
  dialogueId: string;
  pageId?: string;
  section: ValidationSection;
  message: string;
}

type PageWithServer = DialoguePage & { server?: ServerPageSettings };

const itemId = /^(minecraft:)?[a-z0-9_.-]+:[a-z0-9_./-]+(?::\d+)?(?:[:].*)?$/i;
const customItem = /^@(?:OWNER|[0-9a-f-]{36})\/[^:,]+(?::\d+)?$/i;
const variableOperation = /^.+?\s*(=|\+=|-=|\*=|\/=)\s*.+$/u;
const extraCondition = /^.+?\s*(==|=|!=|>=|<=|>|<)\s*.+$/u;

function entries(value: string) {
  return value
    .split(/[,\n]/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function validateItemSpec(value: string) {
  if (!value.trim()) return true;
  return itemId.test(value.trim()) || customItem.test(value.trim());
}

function forEachChoice(choices: DialogueChoice[], visit: (choice: DialogueChoice) => void) {
  for (const choice of choices) {
    visit(choice);
    for (const response of choice.responsePages ?? []) forEachChoice(response.choices, visit);
  }
}

function conditionProblems(condition: ServerCondition) {
  const problems: string[] = [];
  if (condition.mode === 'none') return problems;
  if (['variable', 'both', 'any'].includes(condition.mode) && !condition.variable.trim() && !condition.extraVariables.trim())
    problems.push('변수 조건을 선택했지만 변수 이름이 없습니다.');
  if (['item', 'both', 'any'].includes(condition.mode) && !condition.itemSpec.trim())
    problems.push('아이템 조건을 선택했지만 아이템이 없습니다.');
  if (condition.itemSpec && !validateItemSpec(condition.itemSpec))
    problems.push('아이템 조건 형식이 올바르지 않습니다.');
  for (const expression of entries(condition.extraVariables)) {
    if (!extraCondition.test(expression)) problems.push(`추가 변수 조건 '${expression}' 형식이 올바르지 않습니다.`);
  }
  if (condition.variableLogic === 'not' && entries(condition.extraVariables).length > 1)
    problems.push('NOT 조건은 하나의 추가 변수 조건에 사용하는 것을 권장합니다.');
  return problems;
}

function naturalNextPageId(dialogue: Dialogue, page: PageWithServer, index: number) {
  const server = page.server ?? emptyServerPage();
  if (server.flow.ending) return undefined;
  return server.flow.nextPageId || dialogue.pages[index + 1]?.id;
}

function pageEdges(dialogue: Dialogue, page: PageWithServer, index: number): string[] {
  const server = page.server ?? emptyServerPage();
  const edges = new Set<string>();
  const naturalNext = naturalNextPageId(dialogue, page, index);
  let hasContinuingChoice = false;

  forEachChoice(page.choices, (choice) => {
    if (choice.targetPageId) edges.add(choice.targetPageId);
    else if (!choice.endAfterTarget) hasContinuingChoice = true;
  });

  if (server.flow.conditionalTargetPageId) edges.add(server.flow.conditionalTargetPageId);
  if (naturalNext && (!page.choices.length || hasContinuingChoice)) edges.add(naturalNext);
  return [...edges];
}

function graphIssues(dialogue: Dialogue): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (!dialogue.pages.length) return issues;
  const byId = new Map(dialogue.pages.map((page) => [page.id, page]));
  const edges = new Map(
    dialogue.pages.map((page, index) => [page.id, pageEdges(dialogue, page as PageWithServer, index)]),
  );

  const reachable = new Set<string>();
  const queue = [dialogue.startPageId || dialogue.pages[0].id];
  while (queue.length) {
    const id = queue.shift()!;
    if (reachable.has(id) || !byId.has(id)) continue;
    reachable.add(id);
    edges.get(id)?.forEach((next) => queue.push(next));
  }
  dialogue.pages.forEach((page) => {
    if (!reachable.has(page.id)) {
      issues.push({
        id: `unreachable-${page.id}`,
        severity: 'warning',
        dialogueId: dialogue.id,
        pageId: page.id,
        section: 'flow',
        message: `${page.editorLabel || '페이지'}은 시작 페이지에서 도달할 수 없습니다.`,
      });
    }
  });

  // 일반적인 페이지 순환은 선택지/수동 진행으로 의도적으로 구성할 수 있습니다.
  // Preview의 64회 Flow safety는 operation-only/조건부 자동 이동의 무한 반복을
  // 막기 위한 런타임 보호이므로, 정적 그래프에 순환이 있다는 이유만으로 경고하지 않습니다.

  const terminal = new Set(
    dialogue.pages
      .filter((rawPage, index) => {
        const page = rawPage as PageWithServer;
        const server = page.server ?? emptyServerPage();
        const choiceCanEnd = page.choices.some((choice) => choice.endAfterTarget);
        return server.flow.ending || choiceCanEnd || (!server.flow.nextPageId && index === dialogue.pages.length - 1);
      })
      .map((page) => page.id),
  );
  const canFinish = new Set(terminal);
  let changed = true;
  while (changed) {
    changed = false;
    for (const page of dialogue.pages) {
      if (canFinish.has(page.id)) continue;
      if ((edges.get(page.id) ?? []).some((target) => canFinish.has(target))) {
        canFinish.add(page.id);
        changed = true;
      }
    }
  }
  const start = dialogue.startPageId || dialogue.pages[0].id;
  if (!canFinish.has(start)) {
    issues.push({
      id: `unterminated-${dialogue.id}`,
      severity: 'warning',
      dialogueId: dialogue.id,
      pageId: start,
      section: 'flow',
      message: '현재 흐름에서는 종료 가능한 경로를 찾을 수 없습니다.',
    });
  }
  return issues;
}

export function validateProject(project: RPGProject, manifest: CharacterManifest): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  for (const dialogue of project.dialogues) {
    if (dialogue.name.length > 60) {
      issues.push({
        id: `title-${dialogue.id}`,
        severity: 'error',
        dialogueId: dialogue.id,
        section: 'other',
        message: '대화 제목은 최대 60자입니다.',
      });
    }
    if (dialogue.pages.length > 30) {
      issues.push({
        id: `pages-${dialogue.id}`,
        severity: 'error',
        dialogueId: dialogue.id,
        section: 'flow',
        message: '한 대화에는 최대 30개의 페이지만 사용할 수 있습니다.',
      });
    }

    dialogue.pages.forEach((rawPage, pageIndex) => {
      const page = rawPage as PageWithServer;
      const server = page.server ?? emptyServerPage();
      const prefix = `Page ${pageIndex + 1}`;
      if (visibleLength(page.speaker) > 10) {
        issues.push({
          id: `speaker-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'script',
          message: `${prefix}: 화자는 최대 10자입니다.`,
        });
      }
      if (page.lines.length > 4) {
        issues.push({
          id: `lines-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'script',
          message: `${prefix}: 대사는 최대 4줄입니다.`,
        });
      }
      page.lines.forEach((line, lineIndex) => {
        const length = visibleLength(line);
        if (length > 30) {
          issues.push({
            id: `line-${page.id}-${lineIndex}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'script',
            message: `${prefix} / ${lineIndex + 1}줄이 표시 문자 30자를 ${length - 30}자 초과했습니다.`,
          });
        }
      });
      const validateChoices = (choices: DialogueChoice[], location: string, depth = 0) => {
        if (!choices.length) return;
        if (depth >= 16) {
          issues.push({
            id: `choice-depth-${choices[0].id}-${depth}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'choices',
            message: `${location}: 중첩 선택지는 최대 16단계입니다.`,
          });
          return;
        }
        if (choices.length > 8) {
          issues.push({
            id: `choices-${choices[0].id}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'choices',
            message: `${location}: 선택지는 최대 8개입니다.`,
          });
        }
        choices.forEach((rawChoice, choiceIndex) => {
          const choiceLocation = `${location} / 선택지 ${choiceIndex + 1}`;
          if (!rawChoice.label.trim()) {
            issues.push({
              id: `choice-empty-${rawChoice.id}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: 이름이 비어 있습니다.`,
            });
          }
          if (visibleLength(rawChoice.label) > 10) {
            issues.push({
              id: `choice-length-${rawChoice.id}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: 이름은 최대 10자입니다.`,
            });
          }
          if (visibleLength(rawChoice.speakerOverride ?? '') > 10) {
            issues.push({
              id: `choice-speaker-${rawChoice.id}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: 화자는 최대 10자입니다.`,
            });
          }
          if (rawChoice.targetPageId && !dialogue.pages.some((target) => target.id === rawChoice.targetPageId)) {
            issues.push({
              id: `choice-target-${rawChoice.id}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: 대상 페이지가 존재하지 않습니다.`,
            });
          }
          const condition = rawChoice.server?.condition;
          conditionProblems(condition ?? { ...server.displayCondition, mode: 'none' }).forEach((problem, index) =>
            issues.push({
              id: `choice-condition-${rawChoice.id}-${index}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: ${problem}`,
            }),
          );
          const responses = rawChoice.responsePages ?? [];
          if (responses.length > 30) {
            issues.push({
              id: `choice-responses-${rawChoice.id}`,
              severity: 'error',
              dialogueId: dialogue.id,
              pageId: page.id,
              section: 'choices',
              message: `${choiceLocation}: 후속 대사는 최대 30페이지입니다.`,
            });
          }
          responses.forEach((response, responseIndex) => {
            const responseLocation = `${choiceLocation} / 후속 Page ${responseIndex + 1}`;
            response.lines.forEach((line, lineIndex) => {
              const length = visibleLength(line);
              if (length > 30) {
                issues.push({
                  id: `choice-line-${response.id}-${lineIndex}`,
                  severity: 'error',
                  dialogueId: dialogue.id,
                  pageId: page.id,
                  section: 'choices',
                  message: `${responseLocation} / ${lineIndex + 1}줄이 표시 문자 30자를 ${length - 30}자 초과했습니다.`,
                });
              }
            });
            validateChoices(response.choices, responseLocation, depth + 1);
          });
        });
      };
      validateChoices(page.choices, prefix);

      const character = getCharacter(manifest, page.appearance.characterId);
      if (page.appearance.characterId && !character) {
        issues.push({
          id: `character-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'character',
          message: `${prefix}: Resource Pack에 없는 캐릭터 '${page.appearance.characterId}'를 사용하고 있습니다.`,
        });
      } else if (character) {
        const gender = normalizedGender(character, page.appearance.gender);
        const expression = (page.appearance.expression ?? 'NEUTRAL') as never;
        if (!availableExpressions(character, gender).includes(expression)) {
          issues.push({
            id: `expression-${page.id}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'character',
            message: `${prefix}: 선택한 캐릭터/성별에서 지원하지 않는 표정입니다.`,
          });
        }
      }

      [server.displayCondition, server.flow.condition].forEach((condition, conditionIndex) => {
        conditionProblems(condition).forEach((problem, problemIndex) =>
          issues.push({
            id: `condition-${page.id}-${conditionIndex}-${problemIndex}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: conditionIndex === 0 ? 'condition' : 'flow',
            message: `${prefix}: ${problem}`,
          }),
        );
      });

      for (const operation of entries(server.effects.variablesSet)) {
        if (!variableOperation.test(operation)) {
          issues.push({
            id: `variable-op-${page.id}-${operation}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'effects',
            message: `${prefix}: 변수 연산 '${operation}' 형식이 올바르지 않습니다.`,
          });
        }
      }
      for (const item of [...entries(server.effects.giveItems), ...entries(server.effects.takeItems)]) {
        if (!validateItemSpec(item)) {
          issues.push({
            id: `item-${page.id}-${item}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'effects',
            message: `${prefix}: 아이템 '${item}' 형식이 올바르지 않습니다.`,
          });
        }
      }
      if (server.effects.messageColor && !/^#[0-9a-f]{6}$/i.test(server.effects.messageColor)) {
        issues.push({
          id: `color-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'effects',
          message: `${prefix}: 메시지 색상은 #RRGGBB 형식이어야 합니다.`,
        });
      }
      for (const sound of entries(server.effects.sounds)) {
        const parts = sound.split(':');
        const repeat = Number(parts.at(-1));
        if (Number.isFinite(repeat) && (repeat < 1 || repeat > 10)) {
          issues.push({
            id: `sound-${page.id}-${sound}`,
            severity: 'error',
            dialogueId: dialogue.id,
            pageId: page.id,
            section: 'effects',
            message: `${prefix}: 사운드 repeat는 1~10이어야 합니다.`,
          });
        }
      }
      if (server.flow.nextPageId && !dialogue.pages.some((target) => target.id === server.flow.nextPageId)) {
        issues.push({
          id: `next-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'flow',
          message: `${prefix}: 다음 페이지가 존재하지 않습니다.`,
        });
      }
      if (
        server.flow.conditionalTargetPageId &&
        !dialogue.pages.some((target) => target.id === server.flow.conditionalTargetPageId)
      ) {
        issues.push({
          id: `jump-${page.id}`,
          severity: 'error',
          dialogueId: dialogue.id,
          pageId: page.id,
          section: 'flow',
          message: `${prefix}: 조건부 이동 대상 페이지가 존재하지 않습니다.`,
        });
      }
    });

    issues.push(...graphIssues(dialogue));
  }
  return issues;
}
