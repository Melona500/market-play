export interface DialogueTextSegment {
  text: string;
  color?: string;
  bold: boolean;
  italic: boolean;
  strikethrough: boolean;
}

const styles = '(?:bold|italic|strikethrough)(?:,(?:bold|italic|strikethrough))*';
const inlineFormat = new RegExp(`^\\{#([0-9a-f]{6})(?::(${styles}))?\\}`, 'i');
const wordFormat = new RegExp(`^#([0-9a-f]{6}):(?:(${styles}):)?(\\S+)`, 'i');

function flags(value = '') {
  const selected = new Set(value.toLowerCase().split(',').filter(Boolean));
  return {
    bold: selected.has('bold'),
    italic: selected.has('italic'),
    strikethrough: selected.has('strikethrough'),
  };
}

function withoutVariablePlaceholders(value: string) {
  // Keep the web editor's 30-character validation aligned with the Paper plugin.
  // Variables are runtime substitutions and their token syntax must not consume the
  // authored text budget. Handle RPGMaker {{name}}, Skript %name%, and nested
  // Skript %{list::%uuid of player%}% forms in that order.
  return value
    .replace(/\{\{.+?\}\}/gu, '')
    .replace(/%\{.*?\}%/gu, '')
    .replace(/%[^%\n]+%/gu, '');
}

export function parseDialogueText(
  source: string,
  variables: Record<string, string | number | boolean> = {},
): DialogueTextSegment[] {
  const value = source.replace(/\{\{(.+?)\}\}/gu, (_, name: string) =>
    variables[name] == null ? '' : String(variables[name]),
  );
  const result: DialogueTextSegment[] = [];
  let plain = '';
  let offset = 0;
  let wordStart = true;
  let active: Omit<DialogueTextSegment, 'text'> = { bold: false, italic: false, strikethrough: false };
  const flush = () => {
    if (!plain) return;
    result.push({ text: plain, ...active });
    plain = '';
  };

  while (offset < value.length) {
    const remaining = value.slice(offset);
    const inline = remaining.match(inlineFormat);
    if (inline) {
      flush();
      active = { color: `#${inline[1]}`, ...flags(inline[2]) };
      offset += inline[0].length;
      continue;
    }
    const word = wordStart ? remaining.match(wordFormat) : null;
    if (word) {
      flush();
      result.push({ text: word[3], color: `#${word[1]}`, ...flags(word[2]) });
      offset += word[0].length;
      wordStart = false;
      continue;
    }
    const character = String.fromCodePoint(value.codePointAt(offset)!);
    plain += character;
    wordStart = /\s/u.test(character);
    offset += character.length;
  }
  flush();
  return result;
}

export function visibleLength(value: string) {
  return parseDialogueText(withoutVariablePlaceholders(value)).reduce(
    (total, segment) => total + Array.from(segment.text).length,
    0,
  );
}
