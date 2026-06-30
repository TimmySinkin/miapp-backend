package org.example;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/ai")
public class ClaudeController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CHAT_SYSTEM_PROMPT =
        "Ты — дружелюбный AI-ассистент в приложении-органайзере MiniApp. " +
        "Приложение помогает пользователям отслеживать тренировки, учёбу и личные цели через календарь. " +
        "Ты отвечаешь на русском языке, кратко и по делу, без лишней воды. " +
        "Можешь давать советы по продуктивности, мотивации и планированию дня.";

    private static final String PLAN_SYSTEM_PROMPT =
        "Ты — AI-планировщик в приложении-органайзере. " +
        "Пользователь укажет свою цель и срок. " +
        "Составь план по дням в формате строгого JSON массива, без какого-либо текста до или после. " +
        "Каждый элемент массива должен быть объектом с полями: " +
        "\"day\" (номер дня от 1), \"action\" (короткое название действия на русском), " +
        "\"goal\" (число — целевое количество). " +
        "Пример формата: [{\"day\":1,\"action\":\"Отжимания\",\"goal\":15},{\"day\":1,\"action\":\"Бег\",\"goal\":20}] " +
        "Не добавляй markdown форматирование, только чистый JSON.";

    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        try {
            String reply = callOllama(CHAT_SYSTEM_PROMPT, request.getMessage());
            return ResponseEntity.ok(reply);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

    @PostMapping("/plan")
    public ResponseEntity<String> plan(@RequestBody PlanRequest request) {
        try {
            String userPrompt = "Цель: " + request.getGoal() +
                ". Срок: " + request.getDays() + " дней. Составь план.";
            String reply = callOllama(PLAN_SYSTEM_PROMPT, userPrompt);
            return ResponseEntity.ok(reply);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

    private String callOllama(String systemPrompt, String userMessage) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "qwen2.5");
        body.put("prompt", systemPrompt + "\n\nЗапрос пользователя: " + userMessage);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
            "http://localhost:11434/api/generate",
            HttpMethod.POST,
            entity,
            String.class
        );

        JsonNode json = mapper.readTree(response.getBody());
        return json.get("response").asText();
    }
}