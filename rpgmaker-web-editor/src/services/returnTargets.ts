import type { Dialogue, DialogueChoice } from '../domain/project';

export interface DialogueReturnOption {
  value: string;
  label: string;
  kind: 'page' | 'choice';
  depth: number;
}

function appendChoices(
  output: DialogueReturnOption[],
  choices: DialogueChoice[],
  route: string,
  parentLabel: string,
  depth: number,
) {
  choices.forEach((choice, choiceIndex) => {
    const choiceLabel = `${parentLabel} / 선택지 ${choiceIndex + 1} · ${choice.label || '이름 없음'}`;
    output.push({ value: `CHOICE:${route}#c${choiceIndex}`, label: choiceLabel, kind: 'choice', depth });
    for (const [responseIndex, response] of (choice.responsePages ?? []).entries()) {
      const childRoute = `${route}/c${choiceIndex}/p${responseIndex}`;
      const responseLabel = `${choiceLabel} / 후속 ${responseIndex + 1}`;
      if (response.lines.some((line) => line.trim()))
        output.push({ value: `PAGE:${childRoute}`, label: responseLabel, kind: 'page', depth: depth + 1 });
      appendChoices(output, response.choices, childRoute, responseLabel, depth + 2);
    }
  });
}

export function dialogueReturnOptions(dialogue: Dialogue): DialogueReturnOption[] {
  const output: DialogueReturnOption[] = [];
  dialogue.pages.forEach((page, pageIndex) => {
    const label = `페이지 ${pageIndex + 1} · ${page.editorLabel || page.lines.find(Boolean) || '빈 페이지'}`;
    const route = `p${pageIndex}`;
    if (page.lines.some((line) => line.trim()))
      output.push({ value: `PAGE:${route}`, label, kind: 'page', depth: 0 });
    appendChoices(output, page.choices, route, label, 1);
  });
  return output;
}
