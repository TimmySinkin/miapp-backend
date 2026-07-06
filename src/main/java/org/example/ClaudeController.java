package org.example;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@RestController
@RequestMapping("/api/ai")
public class ClaudeController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // Модель вынесена отдельно — легко поменять в одном месте
    private static final String MODEL_NAME = "qwen2.5";

    private static final String CHAT_SYSTEM_PROMPT =
        "Ты — AI-ассистент в приложении-органайзере MiniApp. " +
        "Помогаешь пользователям достигать любых целей: фитнес, обучение (программирование, дизайн, монтаж, языки), " +
        "творчество, карьера, личное развитие и т.д. " +
        "Отвечай на русском языке, структурированно и с конкретными цифрами. " +
        "Не используй markdown-разметку (звёздочки, решётки). " +
        "Пиши обычным текстом с нумерацией и переносами строк. " +
        "Если пользователь описывает цель — дай полезный совет по подходу к её достижению " +
        "и в конце предложи составить конкретный план по дням, уточнив срок если нужно.";

    private static final String PLAN_SYSTEM_PROMPT =
        "Ты — AI-планировщик задач для календаря приложения MiniApp. " +
        "Пользователь укажет любую цель (фитнес, обучение, творчество, карьера, язык и т.д.) и срок в днях. " +
        "Твоя задача — вернуть ТОЛЬКО валидный JSON-массив, без каких-либо пояснений до или после, " +
        "без markdown-обёртки ```json, без комментариев.\n\n" +
        "Формат каждого элемента массива:\n" +
        "{\"day\": <номер дня, целое число от 1 до заданного срока>, " +
        "\"action\": \"<конкретное измеримое действие>\", " +
        "\"goal\": <число — целевое количество: минуты, страницы, задачи, уроки, строки кода и т.д.>}\n\n" +
        "Требования:\n" +
        "- action должен быть КОНКРЕТНЫМ действием под тип цели:\n" +
        "  * Фитнес: 'Отжимания', 'Приседания', 'Пробежка'\n" +
        "  * Программирование: 'Изучить тему', 'Написать код', 'Решить задачу на LeetCode'\n" +
        "  * Дизайн: 'Изучить инструмент', 'Сделать макет', 'Посмотреть урок'\n" +
        "  * Монтаж: 'Посмотреть урок по монтажу', 'Смонтировать клип', 'Изучить эффект'\n" +
        "  * Язык: 'Выучить слова', 'Послушать подкаст', 'Написать текст'\n" +
        "  * НЕ использовать общие слова типа 'Тренировка', 'Учёба', 'Практика'\n" +
        "- goal должен быть числом с единицей измерения в action (мин, стр, задач и т.д.)\n" +
        "- На каждый день 2-3 конкретных пункта\n" +
        "- Логичная прогрессия сложности по дням\n" +
        "- Заполни все дни от 1 до указанного срока\n\n" +
        "Ответь ТОЛЬКО JSON-массивом, ничего больше.";

    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        try {
            String reply = callOllama(CHAT_SYSTEM_PROMPT, request.getMessage(), 0.7);
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
            String reply = callOllama(PLAN_SYSTEM_PROMPT, userPrompt, 0.15);
            String json = extractJsonArray(reply);
            return ResponseEntity.ok(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Вычищает ответ модели от markdown-обёртки и лишнего текста,
     * оставляя только JSON-массив. Бросает исключение, если массив не найден
     * или не парсится как валидный JSON — чтобы фронт получил внятную ошибку,
     * а не сломанный текст.
     */
    private String extractJsonArray(String raw) throws Exception {
        String cleaned = raw
            .replaceAll("(?i)```json", "")
            .replaceAll("```", "")
            .trim();

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) {
            throw new IllegalStateException("Модель не вернула JSON-массив. Ответ: " + truncate(raw));
        }

        String candidate = cleaned.substring(start, end + 1);

        // Валидируем, что это действительно парсящийся JSON, прежде чем отдавать фронту
        JsonNode parsed = mapper.readTree(candidate);
        if (!parsed.isArray() || parsed.size() == 0) {
            throw new IllegalStateException("JSON-массив пустой или некорректный. Ответ: " + truncate(raw));
        }

        return candidate;
    }

    private String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private String getCurrentDateContext() {
        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'года', EEEE", new Locale("ru"));
        String formatted = today.format(formatter);
        return "Сегодняшняя дата: " + formatted + ".";
    }

    private String callOllama(String systemPrompt, String userMessage, double temperature) throws Exception {
        String dateContext = getCurrentDateContext();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL_NAME);
        body.put("prompt", systemPrompt + "\n\n" + dateContext + "\n\nЗапрос пользователя: " + userMessage);
        body.put("stream", false);

        ObjectNode options = mapper.createObjectNode();
        options.put("temperature", temperature);
        body.set("options", options);

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