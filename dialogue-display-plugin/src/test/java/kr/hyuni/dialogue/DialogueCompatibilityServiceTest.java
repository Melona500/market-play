package kr.hyuni.dialogue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueCompatibilityServiceTest {
    private final DialogueCompatibilityService service = new DialogueCompatibilityService(null);

    @Test
    void validateRejectsInvalidNestedChoiceData() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("choice-count", 1);
        nested.put("choice-1", "12345678901");

        Map<String, Object> rootChoice = new LinkedHashMap<>();
        rootChoice.put("choice-count", 1);
        rootChoice.put("choice-1", "선택");
        rootChoice.put("response-pages-1", List.of("1234567890123456789012345678901"));
        rootChoice.put("response-page-choices-1", Map.of("0", nested));

        Map<String, Object> dialogue = new LinkedHashMap<>();
        dialogue.put("title", "제목");
        dialogue.put("message-pages", List.of("본문"));
        dialogue.put("page-choices", Map.of("0", rootChoice));

        List<String> issues = service.validate(dialogue);

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("표시 문자가 30자")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("선택지 이름은 최대 10자")));
    }

    @Test
    void validateRejectsOverlongTitleAndSpeaker() {
        Map<String, Object> dialogue = new LinkedHashMap<>();
        dialogue.put("title", "가".repeat(61));
        dialogue.put("speaker", "나".repeat(11));
        dialogue.put("message-pages", List.of("본문"));

        List<String> issues = service.validate(dialogue);

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("제목은 최대 60자")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("화자 이름은 최대 10자")));
    }
}
