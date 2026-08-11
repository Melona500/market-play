import { create } from 'zustand';
import type { RPGProject } from '../domain/project';
import { IndexedDbProjectRepository } from '../repositories/IndexedDbProjectRepository';
import { migrateProject } from '../services/migrateProject';
import { createDemoProject, createProject } from '../services/projectFactory';
import { useEditorStore } from './editorStore';

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';

const repository = new IndexedDbProjectRepository();
const saveTimers = new Map<string, number>();

interface ProjectState {
  projects: RPGProject[];
  hydrated: boolean;
  saveStatus: SaveStatus;
  hydrate: () => Promise<void>;
  createProject: (name: string) => Promise<RPGProject>;
  deleteProject: (id: string) => Promise<void>;
  mutateProject: (id: string, mutator: (draft: RPGProject) => void) => void;
  applyHistorySnapshot: (snapshot: RPGProject) => void;
  saveNow: (id: string) => Promise<void>;
}

function scheduleSave(project: RPGProject, setStatus: (status: SaveStatus) => void) {
  const existing = saveTimers.get(project.id);
  if (existing) window.clearTimeout(existing);
  setStatus('saving');
  const timer = window.setTimeout(async () => {
    try {
      await repository.saveProject(project);
      setStatus('saved');
    } catch {
      setStatus('error');
    } finally {
      saveTimers.delete(project.id);
    }
  }, 650);
  saveTimers.set(project.id, timer);
}

export const useProjectStore = create<ProjectState>((set, get) => ({
  projects: [],
  hydrated: false,
  saveStatus: 'idle',
  hydrate: async () => {
    const storedProjects = await repository.loadProjects();
    let projects = storedProjects.map(migrateProject);
    await Promise.all(
      projects
        .filter((project, index) => storedProjects[index]?.schemaVersion !== project.schemaVersion)
        .map((project) => repository.saveProject(project)),
    );
    if (projects.length === 0) {
      const demo = createDemoProject();
      await repository.saveProject(demo);
      projects = [demo];
    }
    set({
      projects: projects.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
      hydrated: true,
      saveStatus: 'saved',
    });
  },
  createProject: async (name) => {
    const project = createProject(name.trim() || '새 프로젝트');
    await repository.saveProject(project);
    set((state) => ({ projects: [project, ...state.projects], saveStatus: 'saved' }));
    return project;
  },
  deleteProject: async (id) => {
    await repository.deleteProject(id);
    set((state) => ({ projects: state.projects.filter((project) => project.id !== id) }));
  },
  mutateProject: (id, mutator) => {
    const current = get().projects.find((project) => project.id === id);
    if (!current) return;
    useEditorStore.getState().record(current);
    const next = structuredClone(current);
    mutator(next);
    next.updatedAt = new Date().toISOString();
    set((state) => ({
      projects: state.projects.map((project) => (project.id === id ? next : project)),
    }));
    scheduleSave(next, (saveStatus) => set({ saveStatus }));
  },
  applyHistorySnapshot: (snapshot) => {
    const next = { ...structuredClone(snapshot), updatedAt: new Date().toISOString() };
    set((state) => ({
      projects: state.projects.map((project) => (project.id === next.id ? next : project)),
    }));
    scheduleSave(next, (saveStatus) => set({ saveStatus }));
  },
  saveNow: async (id) => {
    const project = get().projects.find((candidate) => candidate.id === id);
    if (!project) return;
    const existing = saveTimers.get(id);
    if (existing) window.clearTimeout(existing);
    set({ saveStatus: 'saving' });
    try {
      await repository.saveProject(project);
      set({ saveStatus: 'saved' });
    } catch {
      set({ saveStatus: 'error' });
    }
  },
}));
