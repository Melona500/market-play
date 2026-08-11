import { copyFile, mkdir, readFile, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const editorRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = resolve(editorRoot, '..');
const packRoot = join(repositoryRoot, 'dialogue-resource-pack');
const manifestPath = join(packRoot, 'rpgmaker-character-manifest.json');
const textureRoot = join(packRoot, 'assets', 'dialog', 'textures', 'font');
const outputRoot = join(editorRoot, 'public', 'generated');
const portraitOutput = join(outputRoot, 'portraits');

const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));

await rm(outputRoot, { recursive: true, force: true });
await mkdir(portraitOutput, { recursive: true });
await copyFile(manifestPath, join(outputRoot, 'character-manifest.json'));

const files = new Set(Object.values(manifest.sheets).map((sheet) => sheet.file));
for (const file of files) {
  const source = join(textureRoot, file);
  const destination = join(portraitOutput, file);
  await mkdir(dirname(destination), { recursive: true });
  await copyFile(source, destination);
}

console.log(`Synced ${files.size} RPGMaker portrait sheets from dialogue-resource-pack.`);
