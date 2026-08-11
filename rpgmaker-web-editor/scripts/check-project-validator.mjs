import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) ? `${specifier}.ts` : specifier, context);
  },
});

const { validateProject } = await import('../src/services/projectValidator.ts');
const { createProject, createPage } = await import('../src/services/projectFactory.ts');

const manifest = {
  schemaVersion: 1,
  expressionColumns: {},
  expressionLabels: {},
  sheets: {},
  families: {},
  legacyPortraits: {},
  characters: [],
};

function flowIssueIds(project) {
  return validateProject(project, manifest)
    .filter((issue) => issue.section === 'flow')
    .map((issue) => issue.id);
}

{
  const project = createProject('선택지 기본 진행');
  const dialogue = project.dialogues[0];
  const first = dialogue.pages[0];
  const next = createPage('다음 페이지');
  next.server.flow.ending = true;
  first.choices = [{
    id: crypto.randomUUID(),
    label: '계속',
    responsePages: [{
      id: crypto.randomUUID(),
      lines: ['후속 대사', '', '', ''],
      appearance: { visible: false, speakerVisible: true, inheritPrevious: true, expression: 'NEUTRAL' },
      choices: [],
    }],
  }];
  dialogue.pages.push(next);

  const ids = flowIssueIds(project);
  assert.equal(ids.some((id) => id.startsWith('unreachable-')), false);
  assert.equal(ids.some((id) => id.startsWith('unterminated-')), false);
  assert.equal(ids.some((id) => id.startsWith('cycle-')), false);
}

{
  const project = createProject('선택지 종료');
  const dialogue = project.dialogues[0];
  dialogue.pages[0].choices = [{
    id: crypto.randomUUID(),
    label: '종료',
    endAfterTarget: true,
  }];

  const ids = flowIssueIds(project);
  assert.equal(ids.some((id) => id.startsWith('unterminated-')), false);
}

{
  const project = createProject('삭제된 대상');
  const dialogue = project.dialogues[0];
  dialogue.pages[0].choices = [{
    id: crypto.randomUUID(),
    label: '잘못된 이동',
    targetPageId: 'missing-page',
  }];

  const issues = validateProject(project, manifest);
  assert.equal(issues.some((issue) => issue.id.startsWith('choice-target-') && issue.severity === 'error'), true);
}

console.log('project validator check passed');
