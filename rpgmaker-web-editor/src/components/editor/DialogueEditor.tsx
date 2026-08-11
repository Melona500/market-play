import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { Dialogue, DialoguePage } from '../../domain/project';
import { createDialogue, createId, createPage } from '../../services/projectFactory';
import { useEditorStore } from '../../store/editorStore';
import { useProjectStore } from '../../store/projectStore';
import { MinecraftPreview } from '../preview/MinecraftPreview';
import { EditorToolbar } from './EditorToolbar';
import { PageInspector } from './PageInspector';
import { PageNavigator } from './PageNavigator';
import { ProjectSidebar } from './ProjectSidebar';

export function DialogueEditor() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const project = useProjectStore((state) => state.projects.find((candidate) => candidate.id === projectId));
  const mutateProject = useProjectStore((state) => state.mutateProject);
  const applyHistorySnapshot = useProjectStore((state) => state.applyHistorySnapshot);
  const saveNow = useProjectStore((state) => state.saveNow);
  const saveStatus = useProjectStore((state) => state.saveStatus);

  const activeDialogueId = useEditorStore((state) => state.activeDialogueId);
  const activePageId = useEditorStore((state) => state.activePageId);
  const selectDialogue = useEditorStore((state) => state.selectDialogue);
  const selectPage = useEditorStore((state) => state.selectPage);
  const past = useEditorStore((state) => state.past);
  const future = useEditorStore((state) => state.future);
  const takeUndo = useEditorStore((state) => state.takeUndo);
  const takeRedo = useEditorStore((state) => state.takeRedo);
  const resetHistory = useEditorStore((state) => state.resetHistory);

  useEffect(() => {
    if (!project) return;
    const dialogue = project.dialogues.find((candidate) => candidate.id === activeDialogueId) ?? project.dialogues[0];
    if (dialogue && dialogue.id !== activeDialogueId) selectDialogue(dialogue.id, dialogue.pages[0]?.id);
  }, [project, activeDialogueId, selectDialogue]);

  useEffect(() => {
    resetHistory();
  }, [projectId, resetHistory]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!project) return;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void saveNow(project.id);
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
        event.preventDefault();
        const snapshot = event.shiftKey ? takeRedo(project) : takeUndo(project);
        if (snapshot) applyHistorySnapshot(snapshot);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [project, saveNow, takeUndo, takeRedo, applyHistorySnapshot]);

  if (!project) {
    return <div className="grid min-h-screen place-items-center bg-[#0f1115] text-[#a1a7b3]">프로젝트를 찾을 수 없습니다.</div>;
  }

  const dialogue = project.dialogues.find((candidate) => candidate.id === activeDialogueId) ?? project.dialogues[0];
  if (!dialogue) return null;
  const page = dialogue.pages.find((candidate) => candidate.id === activePageId) ?? dialogue.pages[0];
  if (!page) return null;

  const changePage = (mutator: (draft: DialoguePage) => void) => {
    mutateProject(project.id, (draftProject) => {
      const draftDialogue = draftProject.dialogues.find((candidate) => candidate.id === dialogue.id);
      const draftPage = draftDialogue?.pages.find((candidate) => candidate.id === page.id);
      if (draftPage) mutator(draftPage);
    });
  };

  const selectDialogueAndPage = (nextDialogue: Dialogue) => {
    selectDialogue(nextDialogue.id, nextDialogue.pages[0]?.id);
    resetHistory();
  };

  const createNewDialogue = () => {
    const next = createDialogue(`대화 ${project.dialogues.length + 1}`);
    mutateProject(project.id, (draft) => void draft.dialogues.push(next));
    selectDialogue(next.id, next.pages[0].id);
  };

  const createNewPage = () => {
    if (dialogue.pages.length >= 10) return;
    const next = createPage(`Page ${dialogue.pages.length + 1}`);
    mutateProject(project.id, (draft) => {
      const target = draft.dialogues.find((candidate) => candidate.id === dialogue.id);
      target?.pages.push(next);
    });
    selectPage(next.id);
  };

  const undo = () => {
    const snapshot = takeUndo(project);
    if (snapshot) applyHistorySnapshot(snapshot);
  };

  const redo = () => {
    const snapshot = takeRedo(project);
    if (snapshot) applyHistorySnapshot(snapshot);
  };

  return (
    <main className="flex h-screen flex-col overflow-hidden bg-[#0f1115] text-[#f3f4f6]">
      <EditorToolbar
        projectName={project.name}
        dialogueName={dialogue.name}
        saveStatus={saveStatus}
        canUndo={past.length > 0}
        canRedo={future.length > 0}
        onBack={() => navigate('/')}
        onUndo={undo}
        onRedo={redo}
        onSave={() => void saveNow(project.id)}
      />
      <div className="flex min-h-0 flex-1">
        <nav className="flex w-[280px] shrink-0 flex-col border-r border-[#242933] bg-[#12151a]">
          <ProjectSidebar
            project={project}
            activeDialogueId={dialogue.id}
            onSelectDialogue={selectDialogueAndPage}
            onCreateDialogue={createNewDialogue}
          />
          <PageNavigator
            dialogue={dialogue}
            activePageId={page.id}
            onSelectPage={(target) => selectPage(target.id)}
            onCreatePage={createNewPage}
          />
        </nav>
        <MinecraftPreview page={page} characters={project.characters} />
        <PageInspector
          page={page}
          dialogue={dialogue}
          characters={project.characters}
          onChange={changePage}
          onAddChoice={() =>
            changePage((draft) => {
              if (draft.choices.length < 8) draft.choices.push({ id: createId(), label: '' });
            })
          }
          onRemoveChoice={(choiceId) =>
            changePage((draft) => {
              draft.choices = draft.choices.filter((choice) => choice.id !== choiceId);
            })
          }
        />
      </div>
    </main>
  );
}
