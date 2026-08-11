# RPGMaker Web Editor

Minecraft RPGMaker 플러그인의 대화 제작 기능을 서버와 분리된 데스크톱 웹 에디터로 구현하는 프로젝트입니다.

## 현재 MVP 범위

- Dashboard 및 프로젝트 생성/삭제
- 프로젝트별 대화 생성
- 3-column Dialogue Editor
- 페이지 생성 및 이동 (대화당 최대 10페이지)
- 화자 및 4줄 대사 편집
- Mock 캐릭터 선택 및 표정
- 선택지 생성/삭제 및 대상 페이지 연결
- 서버와 동일한 BEFORE/AFTER 조건 분기 및 종결 흐름 설정
- VARIABLE/ITEM/BOTH/ANY 표시 조건과 AND/OR/XOR/NOT 변수 조합
- 아이템 지급·소모, 변수 연산·삭제·채팅 입력, 사운드·메시지 효과
- 이전 진행 대상과 OP 전용 서버 명령 설정
- 선택지별 표시 조건, 화자 오버라이드, 도착 후 종료 설정
- 입력 즉시 반영되는 Minecraft 스타일 Preview
- IndexedDB 자동 저장
- Ctrl+S 즉시 저장
- Ctrl+Z / Ctrl+Shift+Z Undo / Redo

## 실행

```bash
npm install
npm run dev
```

프로덕션 검증:

```bash
npm run typecheck
npm run build
```

현재 저장소 계층은 `ProjectRepository` 인터페이스를 통해 UI/Editor와 분리되어 있습니다. 이후 Minecraft 서버 연동 단계에서 `MinecraftApiProjectRepository`를 추가할 수 있습니다.
