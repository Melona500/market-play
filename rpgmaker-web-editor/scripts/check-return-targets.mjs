import assert from 'node:assert/strict';
import { dialogueReturnOptions } from '../src/services/returnTargets.ts';

const response = (id, lines, choices = []) => ({
  id,
  lines: [lines, '', '', ''],
  appearance: { visible: true, speakerVisible: true, inheritPrevious: false },
  choices,
});
const choice = (id, label, responsePages = []) => ({ id, label, responsePages });
const nested = choice('nested', '안쪽', [response('nested-response', '안쪽 대사')]);
const root = choice('root', '바깥', [response('root-response', '바깥 대사', [nested])]);
const dialogue = {
  id: 'dialogue',
  name: '검사',
  startPageId: 'page',
  pages: [{
    id: 'page',
    editorLabel: '시작',
    speaker: '',
    lines: ['대사', '', '', ''],
    appearance: { visible: true, speakerVisible: true, inheritPrevious: false },
    choices: [root],
    flow: { ending: false },
    effects: [],
  }],
};

assert.deepEqual(dialogueReturnOptions(dialogue).map(({ value }) => value), [
  'PAGE:p0',
  'CHOICE:p0#c0',
  'PAGE:p0/c0/p0',
  'CHOICE:p0/c0/p0#c0',
  'PAGE:p0/c0/p0/c0/p0',
]);

console.log('Return target routes match the server recursion.');
