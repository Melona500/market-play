export type ManifestGender = 'MALE' | 'FEMALE' | 'NONE';
export type ManifestExpression =
  | 'NEUTRAL'
  | 'HAPPY'
  | 'SAD'
  | 'ANGRY'
  | 'SURPRISED'
  | 'EMBARRASSED';

export interface CharacterSheet {
  file: string;
  columns: number;
  rows: number;
}

export interface CharacterManifestEntry {
  id: string;
  label: string;
  family: 'rpg' | 'village' | 'monster' | 'fixed';
  suffix?: string;
  portrait?: string;
  sheet?: string;
  glyph?: string;
  neutralIndex?: number;
  rows?: Partial<Record<ManifestGender, number>>;
  genders: ManifestGender[];
  expressions?: ManifestExpression[];
}

export interface CharacterManifest {
  schemaVersion: number;
  expressionColumns: Partial<Record<ManifestExpression, number>>;
  expressionLabels: Record<ManifestExpression, string>;
  sheets: Record<string, CharacterSheet>;
  families: Record<
    string,
    {
      neutralSheet?: string;
      neutralGlyphStart?: string;
      emotionSheets?: string[];
      emotionGlyphStarts?: string[];
      rowsPerEmotionSheet?: number;
    }
  >;
  legacyPortraits: Record<string, { sheet: string; glyph: string }>;
  characters: CharacterManifestEntry[];
}

export interface PortraitSprite {
  url: string;
  columns: number;
  rows: number;
  column: number;
  row: number;
}

let manifestPromise: Promise<CharacterManifest> | undefined;

export function loadCharacterManifest(): Promise<CharacterManifest> {
  manifestPromise ??= fetch('/generated/character-manifest.json', { cache: 'no-store' }).then(async (response) => {
    if (!response.ok) throw new Error(`캐릭터 manifest를 불러오지 못했습니다. (${response.status})`);
    return (await response.json()) as CharacterManifest;
  });
  return manifestPromise;
}

export function getCharacter(manifest: CharacterManifest, id?: string) {
  if (!id) return undefined;
  return manifest.characters.find((character) => character.id === id);
}

export function availableExpressions(
  character: CharacterManifestEntry,
  gender: ManifestGender,
): ManifestExpression[] {
  if (character.expressions?.length) return character.expressions;
  if (character.family === 'rpg' || character.family === 'village') {
    const base: ManifestExpression[] = ['NEUTRAL', 'HAPPY', 'SAD', 'ANGRY', 'SURPRISED'];
    if (gender === 'FEMALE') base.push('EMBARRASSED');
    return base;
  }
  return ['NEUTRAL'];
}

export function normalizedGender(
  character: CharacterManifestEntry,
  value?: string,
): ManifestGender {
  if (character.genders.length === 1) return character.genders[0];
  return value === 'FEMALE' || value === 'female' ? 'FEMALE' : 'MALE';
}

export function resolvePortraitId(
  character: CharacterManifestEntry,
  gender: ManifestGender,
): string {
  if (character.family === 'rpg' || character.family === 'village') {
    return `${gender === 'FEMALE' ? 'FEMALE' : 'MALE'}_${character.suffix}`;
  }
  return character.portrait ?? character.id;
}

export function findCharacterByPortrait(
  manifest: CharacterManifest,
  portrait?: string,
): { character: CharacterManifestEntry; gender: ManifestGender } | undefined {
  if (!portrait) return undefined;
  for (const character of manifest.characters) {
    for (const gender of character.genders) {
      if (resolvePortraitId(character, gender) === portrait) return { character, gender };
    }
  }
  const legacy = manifest.characters.find((character) => character.id === portrait);
  return legacy ? { character: legacy, gender: normalizedGender(legacy) } : undefined;
}

export function portraitSprite(
  manifest: CharacterManifest,
  character: CharacterManifestEntry,
  gender: ManifestGender,
  expression: ManifestExpression,
): PortraitSprite | undefined {
  let sheetId: string | undefined;
  let row = 0;
  let column = 0;

  if (character.family === 'fixed') {
    sheetId = character.sheet;
  } else if (character.family === 'monster') {
    sheetId = manifest.families.monster?.neutralSheet ?? 'neutral';
    const sheet = manifest.sheets[sheetId];
    const index = character.neutralIndex ?? 0;
    row = Math.floor(index / sheet.columns);
    column = index % sheet.columns;
  } else {
    const family = manifest.families[character.family];
    const sourceRow = character.rows?.[gender] ?? character.rows?.MALE ?? 0;
    const allowed = availableExpressions(character, gender);
    const resolved = allowed.includes(expression) ? expression : 'NEUTRAL';

    if (resolved === 'NEUTRAL') {
      sheetId = family?.neutralSheet;
      const sheet = sheetId ? manifest.sheets[sheetId] : undefined;
      if (!sheet) return undefined;
      row = Math.floor(sourceRow / sheet.columns);
      column = sourceRow % sheet.columns;
    } else {
      const rowsPerSheet = family?.rowsPerEmotionSheet ?? 6;
      const sheetIndex = Math.floor(sourceRow / rowsPerSheet);
      sheetId = family?.emotionSheets?.[sheetIndex];
      row = sourceRow % rowsPerSheet;
      column = manifest.expressionColumns[resolved] ?? 0;
    }
  }

  if (!sheetId) return undefined;
  const sheet = manifest.sheets[sheetId];
  if (!sheet) return undefined;
  return {
    url: `/generated/portraits/${sheet.file}`,
    columns: sheet.columns,
    rows: sheet.rows,
    column,
    row,
  };
}
