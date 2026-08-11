import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const vite = await createServer({ root, appType: 'custom', logLevel: 'silent', server: { middlewareMode: true } });

try {
  const { migrateProject } = await vite.ssrLoadModule('/src/services/migrateProject.ts');
  const project = {
    id: 'legacy', name: 'legacy', schemaVersion: 2, createdAt: '', updatedAt: '', characters: [], variables: [], items: [],
    dialogues: [{
      id: 'dialogue', name: 'dialogue', startPageId: 'page',
      pages: [{
        id: 'page', speaker: '', lines: ['top'], appearance: {}, flow: { ending: false }, effects: [], server: {},
        choices: [{
          id: 'choice', label: 'choice', server: {},
          responsePages: [{
            id: 'response', lines: ['nested'], appearance: {}, server: { effects: { message: 'kept' } },
            choices: [{ id: 'nested-choice', label: 'nested', server: {} }],
          }],
        }],
      }],
    }],
  };

  const migrated = migrateProject(project);
  const response = migrated.dialogues[0].pages[0].choices[0].responsePages[0];
  assert.equal(migrated.schemaVersion, 3);
  assert.deepEqual(response.lines, ['nested', '', '', '']);
  assert.equal(response.appearance.visible, true);
  assert.equal(response.server.effects.message, 'kept');
  assert.equal(response.server.effects.variablesSet, '');
  assert.equal(response.choices[0].server.condition.mode, 'none');
  assert.equal(project.schemaVersion, 2);
} finally {
  await vite.close();
}
