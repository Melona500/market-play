import { create } from 'zustand';
import type { RPGProject } from '../domain/project';

const HISTORY_LIMIT = 60;

interface EditorState {
  activeDialogueId?: string;
  activePageId?: string;
  activeChoiceId?: string;
  past: RPGProject[];
  future: RPGProject[];
  selectDialogue: (dialogueId: string, firstPageId?: string) => void;
  selectPage: (pageId: string) => void;
  selectChoice: (choiceId?: string) => void;
  record: (project: RPGProject) => void;
  takeUndo: (current: RPGProject) => RPGProject | null;
  takeRedo: (current: RPGProject) => RPGProject | null;
  resetHistory: () => void;
}

const cloneProject = (project: RPGProject) => structuredClone(project);

export const useEditorStore = create<EditorState>((set, get) => ({
  past: [],
  future: [],
  selectDialogue: (dialogueId, firstPageId) =>
    set({ activeDialogueId: dialogueId, activePageId: firstPageId, activeChoiceId: undefined }),
  selectPage: (pageId) => set({ activePageId: pageId, activeChoiceId: undefined }),
  selectChoice: (choiceId) => set({ activeChoiceId: choiceId }),
  record: (project) =>
    set((state) => ({
      past: [...state.past.slice(-(HISTORY_LIMIT - 1)), cloneProject(project)],
      future: [],
    })),
  takeUndo: (current) => {
    const { past } = get();
    const previous = past.at(-1);
    if (!previous) return null;
    set((state) => ({
      past: state.past.slice(0, -1),
      future: [cloneProject(current), ...state.future].slice(0, HISTORY_LIMIT),
    }));
    return cloneProject(previous);
  },
  takeRedo: (current) => {
    const { future } = get();
    const next = future[0];
    if (!next) return null;
    set((state) => ({
      past: [...state.past, cloneProject(current)].slice(-HISTORY_LIMIT),
      future: state.future.slice(1),
    }));
    return cloneProject(next);
  },
  resetHistory: () => set({ past: [], future: [] }),
}));
