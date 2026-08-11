import type { RPGProject } from '../domain/project';

export interface ProjectRepository {
  loadProjects(): Promise<RPGProject[]>;
  loadProject(id: string): Promise<RPGProject | null>;
  saveProject(project: RPGProject): Promise<void>;
  deleteProject(id: string): Promise<void>;
}
