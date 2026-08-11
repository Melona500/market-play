import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) ? `${specifier}.ts` : specifier, context);
  },
});

const { advancePreview, choosePreview, createPreviewState } = await import('../src/services/previewEngine.ts');
const { parseDialogueText, visibleLength } = await import('../src/services/dialogueText.ts');
const appearance = { characterId: '', gender: 'NONE', expression: 'NEUTRAL', visible: false };
const dialogue = {
  id: 'check',
  title: 'check',
  speaker: '기본',
  startPageId: 'p0',
  pages: [
    {
      id: 'p0',
      speaker: '기본',
      lines: ['시작', '', '', ''],
      appearance,
      choices: [{
        id: 'c0',
        label: '선택',
        speakerOverride: '#FF0000:bold:후속',
        responsePages: [{ id: 'r0', lines: ['후속', '', '', ''], appearance, choices: [] }],
      }],
    },
    { id: 'p1', speaker: '다음', lines: ['끝', '', '', ''], appearance, choices: [] },
  ],
};

let state = createPreviewState(dialogue);
state = choosePreview(dialogue, state, dialogue.pages[0].choices[0]);
assert.equal(state.currentPageId, 'r0');
assert.equal(state.speaker, '#FF0000:bold:후속');
state = advancePreview(dialogue, state);
assert.equal(state.currentPageId, 'p1');
assert.equal(visibleLength('{{player_name}} #FF0000:bold:완료'), 3);
assert.equal(visibleLength('{{skript:quest::%uuid of player%}}완료'), 2);
assert.equal(visibleLength("123%player's location%456"), 6);
assert.equal(visibleLength('123%{quest::%uuid of player%}%456'), 6);
assert.equal(visibleLength('123{{한글_변수}}456'), 6);
assert.deepEqual(parseDialogueText('#FF0000:bold,italic:완료')[0], {
  text: '완료', color: '#FF0000', bold: true, italic: true, strikethrough: false,
});

console.log('preview engine check passed');
