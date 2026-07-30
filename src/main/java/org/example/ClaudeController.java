package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/ai")
public class ClaudeController {

    // Таймаут по умолчанию у RestTemplate() — БЕСКОНЕЧНЫЙ. Явно задаём разумные
    // границы: 10 сек на подключение, 2 минуты на чтение (Groq работает быстро,
    // это далеко не Ollama, но запас на сеть/ретраи не помешает).
    private final RestTemplate restTemplate = buildRestTemplateWithTimeouts();

    private static RestTemplate buildRestTemplateWithTimeouts() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(2 * 60_000);
        return new RestTemplate(factory);
    }
    private final ObjectMapper mapper = new ObjectMapper();

    // Groq — облачный API, доступный откуда угодно (в отличие от Ollama на
    // localhost, которая недоступна бэкенду, задеплоенному на Render).
    // Ключ берём из переменной окружения — задайте GROQ_API_KEY в настройках
    // сервиса на Render (или локально через export GROQ_API_KEY=...).
    // Получить ключ: https://console.groq.com/keys (бесплатно, без карты).
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    // Модель вынесена отдельно — легко поменять в одном месте.
    // qwen/qwen3-32b — бесплатный тариф Groq (1000 запросов/день, 6000 токенов/мин
    // на момент написания), надёжный tool calling, крупнее прежней локальной 14b.
    private static final String MODEL_NAME = "qwen/qwen3-32b";
    // Vision-модель для запросов с изображениями — мультимодальная (preview на
    // стороне Groq на момент написания, состав моделей может меняться).
    private static final String VISION_MODEL_NAME = "qwen/qwen3.6-27b";

    private static final String CHAT_SYSTEM_PROMPT =
        "Ты — AI-ассистент в приложении-органайзере MiniApp. " +
        "Помогаешь пользователям достигать любых целей: фитнес, программирование, дизайн, монтаж, языки, карьера.\n\n" +
        "%LANGUAGE_RULE%\n\n" +
        "Правила оформления (всегда используй Markdown):\n" +
        "- Используй заголовки ## и ### для разделов\n" +
        "- Используй маркированные и нумерованные списки\n" +
        "- Выделяй ключевые мысли **жирным**\n" +
        "- Используй таблицы если это делает ответ понятнее\n" +
        "- Код оформляй в блоках с указанием языка\n" +
        "- Не добавляй лишних пустых строк\n" +
        "- Ответ должен выглядеть профессионально как статья\n\n" +
        "Давай конкретные цифры и факты. " +
        "Если для точного совета не хватает данных — задай уточняющий вопрос. " +
        "В конце предложи составить план по дням если цель требует регулярных действий.\n\n" +
        "КРИТИЧЕСКИ ВАЖНОЕ ОГРАНИЧЕНИЕ: ты НЕ можешь сам записывать что-либо в календарь пользователя, " +
        "сохранять план, добавлять задачи или совершать любые другие действия в приложении — " +
        "ты только генерируешь текст ответа. Реальное сохранение плана в календарь происходит " +
        "ИСКЛЮЧИТЕЛЬНО через нажатие пользователем кнопки в интерфейсе после того, как план предложен " +
        "с явным сроком в днях. Поэтому НИКОГДА не пиши фразы вроде «внесу в календарь», «добавил план», " +
        "«сохранено», «готово, план уже в календаре» и подобные — это будет ложью, ничего не сохранится. " +
        "Если пользователь просит внести план в календарь — предложи ему явно указать срок в днях " +
        "(например, «на 7 дней» или «на 14 дней»), после чего в интерфейсе появится кнопка подтверждения, " +
        "и объясни, что план попадёт в календарь только после нажатия этой кнопки.\n\n" +
        "У тебя есть точный календарь ближайших 14 дней (см. ниже, после этого промпта) — " +
        "используй его, чтобы безошибочно называть конкретную дату для любого дня недели " +
        "или относительного слова («воскресенье», «завтра», «на выходных», «на этой неделе»). " +
        "Не вычисляй даты самостоятельно в уме — всегда сверяйся со списком.\n\n" +
        "У тебя есть инструмент web_search. Вызывай его сам, когда для ответа нужны СВЕЖИЕ " +
        "или конкретные фактические данные, которых ты не можешь точно знать: актуальные фильмы/сериалы/новинки, " +
        "текущие цены, новости, расписания, курсы и любые другие быстро меняющиеся факты. " +
        "НЕ вызывай его для советов общего характера, объяснений понятий, планов тренировок/учёбы и т.п. — " +
        "там он не нужен.\n" +
        "КРИТИЧЕСКИ ВАЖНО: если тебе нужен поиск — реально ВЫЗОВИ инструмент web_search " +
        "(через tool_calls), а НЕ пиши пользователю текстом список формулировок вида " +
        "«попробуйте поискать по запросу...» или «вот какие запросы стоит использовать». " +
        "Пользователь не может сам вызвать инструмент — это должен сделать только ты. " +
        "Никогда не отвечай предложением поисковых запросов вместо реального вызова инструмента.\n" +
        "КРИТИЧЕСКИ ВАЖНО при использовании web_search: отвечай ТОЛЬКО на основе того, что реально " +
        "вернул поиск. Никогда не дополняй результаты выдуманными названиями, именами или фактами. " +
        "Если поиск дал мало релевантного — честно скажи об этом и предложи уточнить запрос, " +
        "вместо того чтобы дофантазировать недостающее.\n" +
        "ОСОБО КРИТИЧЕСКИ ВАЖНО: если web_search вернул пустой результат или явно нерелевантные " +
        "ссылки — НЕ приводи вообще НИКАКОГО списка фильмов/названий из своей памяти, даже под видом " +
        "«проверенных», «классических» или «широко известных». Единственный допустимый ответ в этом " +
        "случае — сообщить, что поиск не дал результатов, и предложить переформулировать запрос " +
        "(например, уточнить жанр, десятилетие или страну). Подмена пустого результата поиска списком " +
        "из собственной памяти — это то же самое, что придумать факты, и запрещено без исключений.";

    private static final String PLAN_SYSTEM_PROMPT =
        "Ты — AI-планировщик задач для календаря приложения MiniApp. " +
        "Пользователь укажет любую цель (фитнес, обучение, творчество, карьера, язык и т.д.) и срок в днях. " +
        "Твоя задача — вернуть ТОЛЬКО валидный JSON-массив, без каких-либо пояснений до или после, " +
        "без markdown-обёртки ```json, без комментариев.\n\n" +
        "Формат каждого элемента массива:\n" +
        "{\"day\": <номер дня, целое число от 1 до заданного срока>, " +
        "\"action\": \"<конкретное измеримое действие>\", " +
        "\"goal\": <число — целевое количество: минуты, страницы, задачи, уроки, строки кода и т.д.>, " +
        "\"category\": \"<одно из: tasks, goals, leisure>\"}\n\n" +
        "Требования:\n" +
        "- action должен быть КОНКРЕТНЫМ действием под тип цели:\n" +
        "  * Фитнес: 'Отжимания', 'Приседания', 'Пробежка'\n" +
        "  * Программирование: 'Изучить тему', 'Написать код', 'Решить задачу на LeetCode'\n" +
        "  * Дизайн: 'Изучить инструмент', 'Сделать макет', 'Посмотреть урок'\n" +
        "  * Монтаж: 'Посмотреть урок по монтажу', 'Смонтировать клип', 'Изучить эффект'\n" +
        "  * Язык: 'Выучить слова', 'Послушать подкаст', 'Написать текст'\n" +
        "  * НЕ использовать общие слова типа 'Тренировка', 'Учёба', 'Практика'\n" +
        "- goal должен быть числом с единицей измерения в action (мин, стр, задач и т.д.)\n" +
        "- category — классифицируй КАЖДОЕ действие ровно в одну из трёх категорий (используй именно эти значения, на английском, без изменений):\n" +
        "  * \"goals\" — действие напрямую продвигает заявленную личную цель пользователя (это будет большинство пунктов плана: тренировки, обучение, практика по теме цели)\n" +
        "  * \"tasks\" — рутинное организационное действие, не являющееся содержательным шагом к цели (планирование, подготовка инвентаря/материалов, ведение дневника прогресса и т.п.)\n" +
        "  * \"leisure\" — отдых, восстановление или переключение внимания (день отдыха, лёгкая прогулка, растяжка для расслабления, не как часть тренировочной нагрузки)\n" +
        "  * Если сомневаешься — выбирай \"goals\", так как план в целом посвящён достижению личной цели\n" +
        "- На каждый день 2-3 конкретных пункта\n" +
        "- Логичная прогрессия сложности по дням\n" +
        "- Заполни все дни от 1 до указанного срока\n\n" +
        "Ответь ТОЛЬКО JSON-массивом, ничего больше. " +
        "КРИТИЧЕСКИ ВАЖНО: массив ДОЛЖЕН содержать записи для КАЖДОГО дня от 1 до указанного срока. " +
        "Если срок 14 дней — в массиве должны быть записи с day:1, day:2, ... day:14. " +
        "Не останавливайся на первом дне. Не сокращай. Не пиши резюме. Только JSON.";

    // Возвращает название языка в предложном падеже для явной инструкции
    // модели — конкретное "на русском" работает надёжнее, чем просить
    // модель самой определить язык (qwen2.5 иногда "съезжает" на китайский
    // в длинных ответах, особенно если инструкция про язык расплывчата).
    private String languageName(String lang) {
        if (lang == null) return null;
        switch (lang.toLowerCase(Locale.ROOT)) {
            case "ru": return "русском";
            case "en": return "английском";
            case "uk": return "украинском";
            case "es": return "испанском";
            case "de": return "немецком";
            case "fr": return "французском";
            default: return null;
        }
    }

    // Строгое языковое правило, которое подставляется и в системный промпт,
    // и повторно — сразу после запроса пользователя, ближе к месту генерации
    // (для завершающих не-chat моделей типа base qwen2.5 через /api/generate
    // инструкции в конце промпта соблюдаются надёжнее, чем в начале).
    private String buildLanguageRule(String lang) {
        String name = languageName(lang);
        if (name != null) {
            return "Отвечай ИСКЛЮЧИТЕЛЬНО на " + name + " языке. " +
                "Ни одного слова, буквы или иероглифа на китайском, английском или любом другом языке — " +
                "только " + name + ". Если чувствуешь, что переключаешься на другой язык, " +
                "остановись и продолжи на " + name + ".";
        }
        return "Определи язык сообщения пользователя и отвечай ИСКЛЮЧИТЕЛЬНО на этом языке, " +
            "без переключения на китайский, английский или любой другой язык посреди ответа.";
    }

    // Ограничение на количество последних сообщений истории, которые
    // передаём модели — иначе промпт станет слишком длинным и медленным.
    private static final int MAX_HISTORY_MESSAGES = 20;

    // Превращает историю переписки в читаемый текстовый транскрипт вида
    // "Пользователь: ...\nАссистент: ...", который модель видит как контекст
    // диалога. Ollama /api/generate не хранит состояние между запросами —
    // без этого агент "забывает" всё, что было сказано раньше в этом чате.
    private String buildHistoryTranscript(java.util.List<HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            HistoryMessage m = history.get(i);
            if (m.getText() == null || m.getText().isBlank()) continue;
            String speaker = "user".equals(m.getRole()) ? "Пользователь" : "Ассистент";
            sb.append(speaker).append(": ").append(m.getText()).append("\n\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private static final String FILE_SYSTEM_PROMPT =
        "Ты — AI-ассистент в приложении MiniApp, специализирующийся на работе с прикреплёнными файлами " +
        "(документы, заметки, код, текстовые выгрузки).\n\n" +
        "%LANGUAGE_RULE%\n\n" +
        "Пользователь прикрепил файл — он вставлен ниже в сообщении с пометкой " +
        "[Прикреплённый файл \"...\"]. Твоя задача:\n" +
        "- Явно опираться на содержимое файла в ответе, а не на общие рассуждения\n" +
        "- Если просят саммари — дай краткую выжимку сути, а не пересказ построчно\n" +
        "- Если просят найти/извлечь что-то конкретное — процитируй только нужный фрагмент (коротко)\n" +
        "- Если вопрос не связан с содержимым файла — отвечай как обычно, файл не мешает\n" +
        "- Если файлов несколько — разбирай их по отдельности, не смешивая контекст\n" +
        "- Используй Markdown: заголовки, списки, выделение ключевых моментов **жирным**";

    // Простой веб-поиск без API-ключа через HTML-версию DuckDuckGo (lite).
    // Возвращает текстовый блок с топ-результатами (заголовок + сниппет + ссылка) —
    // это "исполнитель" инструмента web_search, который модель вызывает сама через tool calling.
    private String webSearch(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://lite.duckduckgo.com/lite/?q=" + encoded))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(java.time.Duration.ofSeconds(6))
                .GET()
                .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            // Грубый, но зависимостей не требующий парсинг HTML lite-версии DDG:
            // строки результатов вида <a class="result-link" href="...">Заголовок</a>
            // и рядом <td class="result-snippet">Сниппет</td>.
            java.util.regex.Matcher linkMatcher = java.util.regex.Pattern
                .compile("class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
            java.util.regex.Matcher snippetMatcher = java.util.regex.Pattern
                .compile("class=\"result-snippet\"[^>]*>(.*?)</td>", java.util.regex.Pattern.DOTALL)
                .matcher(html);

            java.util.List<String> links = new java.util.ArrayList<>();
            java.util.List<String> titleTexts = new java.util.ArrayList<>();
            while (linkMatcher.find() && links.size() < 6) {
                links.add(linkMatcher.group(1));
                titleTexts.add(stripHtmlTags(linkMatcher.group(2)));
            }
            java.util.List<String> snippets = new java.util.ArrayList<>();
            while (snippetMatcher.find() && snippets.size() < 6) {
                snippets.add(stripHtmlTags(snippetMatcher.group(1)));
            }

            if (titleTexts.isEmpty()) {
                return "(веб-поиск не вернул результатов по запросу \"" + query + "\")";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Результаты веб-поиска по запросу \"").append(query).append("\":\n");
            for (int i = 0; i < titleTexts.size(); i++) {
                sb.append(i + 1).append(". ").append(titleTexts.get(i));
                if (i < snippets.size() && !snippets.get(i).isBlank()) {
                    sb.append(" — ").append(snippets.get(i));
                }
                if (i < links.size()) {
                    sb.append(" (").append(links.get(i)).append(")");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "(веб-поиск не удался: " + e.getMessage() + ")";
        }
    }

    private String stripHtmlTags(String s) {
        return s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    // Описание инструмента web_search в формате Ollama/OpenAI tool calling —
    // модель сама решает, вызывать его или нет, ориентируясь на это описание
    // и на инструкции из системного промпта.
    private ObjectNode buildWebSearchTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = mapper.createObjectNode();
        function.put("name", "web_search");
        function.put("description",
            "Ищет в интернете актуальную информацию: новинки фильмов/сериалов, новости, цены, расписания и т.п. " +
            "Используй, когда для точного ответа нужны свежие факты, которых модель не может точно знать сама.");
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        ObjectNode queryProp = mapper.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "Поисковый запрос на русском или английском, максимально конкретный");
        properties.set("query", queryProp);
        parameters.set("properties", properties);
        com.fasterxml.jackson.databind.node.ArrayNode required = mapper.createArrayNode();
        required.add("query");
        parameters.set("required", required);
        function.set("parameters", parameters);
        tool.set("function", function);
        return tool;
    }

    // Настоящий tool calling через Groq /chat/completions (OpenAI-совместимый
    // формат) — раньше это был локальный Ollama /api/chat. Модель сама решает
    // по ходу диалога, нужен ли ей web_search, и может вызвать его несколько
    // раз подряд, прежде чем дать финальный текстовый ответ.
    private String callOllamaChatWithTools(
        String systemPrompt,
        String userMessage,
        String historyText,
        boolean withTools
    ) throws Exception {
        String dateContext = getCurrentDateContext();
        StringBuilder sysContent = new StringBuilder();
        sysContent.append(systemPrompt).append("\n\n").append(dateContext);
        if (historyText != null && !historyText.isBlank()) {
            sysContent.append("\n\nИстория переписки в этом чате (для контекста, не переспрашивай то, что уже обсуждалось):\n\n")
                .append(historyText);
        }

        java.util.List<ObjectNode> messages = new java.util.ArrayList<>();
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", sysContent.toString());
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        com.fasterxml.jackson.databind.node.ArrayNode toolsArray = null;
        if (withTools) {
            toolsArray = mapper.createArrayNode();
            toolsArray.add(buildWebSearchTool());
        }

        int maxIterations = 4; // защита от зацикливания, если модель бесконечно зовёт инструмент
        for (int iter = 0; iter < maxIterations; iter++) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL_NAME);
            body.put("stream", false);
            body.put("temperature", 0.7);
            // qwen3 на Groq: reasoning_effort "default" оставляет модели её
            // обычное рассуждение перед ответом (важно для надёжного tool
            // calling — без этого модель чаще просто текстом пересказывает,
            // что стоило бы поискать, вместо реального вызова инструмента).
            body.put("reasoning_effort", "default");

            com.fasterxml.jackson.databind.node.ArrayNode messagesArray = mapper.createArrayNode();
            for (ObjectNode m : messages) messagesArray.add(m);
            body.set("messages", messagesArray);
            if (toolsArray != null) body.set("tools", toolsArray);

            HttpEntity<String> entity = new HttpEntity<>(body.toString(), groqHeaders());

            ResponseEntity<String> response = restTemplate.exchange(
                GROQ_URL,
                HttpMethod.POST,
                entity,
                String.class
            );

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode choices = json.get("choices");
            JsonNode message = (choices != null && choices.size() > 0) ? choices.get(0).get("message") : null;
            JsonNode toolCalls = message != null ? message.get("tool_calls") : null;

            if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0) {
                // Модель попросила вызвать инструмент(ы) — добавляем её сообщение
                // с tool_calls в историю и выполняем каждый вызов реально.
                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText("") : "");
                assistantMsg.set("tool_calls", toolCalls);
                messages.add(assistantMsg);

                for (JsonNode toolCall : toolCalls) {
                    JsonNode function = toolCall.get("function");
                    String name = function != null && function.has("name") ? function.get("name").asText("") : "";
                    // В OpenAI-совместимом формате (в отличие от Ollama) каждый
                    // tool_call имеет свой "id", и ответное сообщение ОБЯЗАНО
                    // содержать tool_call_id с тем же значением — иначе Groq
                    // не сможет сопоставить результат с вызовом.
                    String toolCallId = toolCall.has("id") ? toolCall.get("id").asText("") : "";

                    String query = "";
                    JsonNode args = function != null ? function.get("arguments") : null;
                    if (args != null) {
                        // arguments у OpenAI-совместимых API — это JSON-СТРОКА,
                        // а не готовый объект (в отличие от того, как отдавала
                        // Ollama) — её нужно распарсить отдельно.
                        if (args.isTextual()) {
                            try {
                                JsonNode parsedArgs = mapper.readTree(args.asText());
                                if (parsedArgs.has("query")) query = parsedArgs.get("query").asText("");
                            } catch (Exception ignored) {
                                // если вдруг всё же пришёл не JSON — просто нет query
                            }
                        } else if (args.has("query")) {
                            query = args.get("query").asText("");
                        }
                    }

                    String result = "web_search".equals(name)
                        ? webSearch(query)
                        : "(неизвестный инструмент: " + name + ")";

                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
                continue; // следующая итерация — модель увидит результаты инструментов
            }

            // Нет вызовов инструментов — это финальный текстовый ответ
            return message != null && message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText("") : "";
        }

        return "Не удалось получить ответ после нескольких попыток вызова инструментов.";
    }

    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        try {
            StringBuilder userMessage = new StringBuilder(
                request.getMessage() == null ? "" : request.getMessage()
            );
            java.util.List<String> images = new java.util.ArrayList<>();
            boolean hasTextAttachment = false;

            if (request.getAttachments() != null) {
                for (Attachment att : request.getAttachments()) {
                    if ("text".equals(att.getType())) {
                        hasTextAttachment = true;
                        userMessage.append("\n\n[Прикреплённый файл \"")
                            .append(att.getName())
                            .append("\"]:\n")
                            .append(att.getContent());
                    } else if ("image".equals(att.getType())) {
                        images.add(att.getContent());
                    }
                }
            }

            String languageRule = buildLanguageRule(request.getLang());
            String historyText = buildHistoryTranscript(request.getHistory());
            boolean hasImages = !images.isEmpty();

            // Если у чата уже есть заявленная цель (goalText — самое первое
            // сообщение, с которого создан чат) — жёстко ограничиваем разговор
            // этой темой. Без этого пользователь может в чате про монтаж видео
            // между делом обсудить план по похудению, и это создаёт путаницу
            // при сохранении в календарь (см. handleSaveToCalendar) и вообще
            // размывает смысл "один чат — одна цель".
            String topicLock = "";
            if (request.getGoalText() != null && !request.getGoalText().isBlank()) {
                topicLock = "\n\nЭТОТ ЧАТ ЖЁСТКО ОГРАНИЧЕН ОДНОЙ ТЕМОЙ — заявленной целью: \"" +
                    request.getGoalText() + "\".\n" +
                    "Разрешено: обсуждать прогресс по этой цели, корректировать план, отвечать на вопросы, " +
                    "напрямую относящиеся к ней (детали упражнений, техник, материалов и т.п. в рамках именно этой темы).\n" +
                    "ЗАПРЕЩЕНО: обсуждать другую, не связанную цель или тему (например, если цель чата — " +
                    "\"научиться монтажу\", не помогай в этом же чате с планом похудения, рекомендациями " +
                    "фильмов и т.п.). Если пользователь просит что-то явно постороннее — вежливо откажи " +
                    "и предложи открыть для этого новый чат, не пытайся встроить постороннюю тему в текущий план.";
            }

            String reply;
            if (hasImages) {
                // Vision-запросы остаются на старом простом /api/generate —
                // qwen2.5vl не даёт надёжного tool calling, а картинка+интернет-поиск
                // одновременно почти никогда не нужны в этом приложении.
                String systemPrompt = CHAT_SYSTEM_PROMPT.replace("%LANGUAGE_RULE%", languageRule) + topicLock;
                reply = callOllama(systemPrompt, userMessage.toString(), 0.7, images, languageRule, historyText);
            } else if (hasTextAttachment) {
                // Файловый сценарий: фокус на содержимом вложения, инструмент
                // web_search тут не нужен и мог бы отвлекать модель от файла.
                String systemPrompt = FILE_SYSTEM_PROMPT.replace("%LANGUAGE_RULE%", languageRule) + topicLock;
                reply = callOllamaChatWithTools(systemPrompt, userMessage.toString(), historyText, false);
            } else {
                // Обычный текстовый сценарий (включая "фильмы") — с инструментом
                // web_search. Модель САМА решает, вызывать его или нет,
                // ориентируясь на описание инструмента и системный промпт,
                // а не по жёсткому списку ключевых слов в Java-коде.
                String systemPrompt = CHAT_SYSTEM_PROMPT.replace("%LANGUAGE_RULE%", languageRule) + topicLock;
                reply = callOllamaChatWithTools(systemPrompt, userMessage.toString(), historyText, true);
            }

            return ResponseEntity.ok(reply);
        } catch (Exception e) {
            // Раньше исключение просто проглатывалось — в консоли бэкенда было
            // тихо даже при реальном 500. Теперь печатаем стек-трейс, иначе
            // единственный источник информации об ошибке — обрезанный текст
            // в теле ответа, который фронт вдобавок не всегда показывает.
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

   @PostMapping("/plan")
    public ResponseEntity<String> plan(@RequestBody PlanRequest request) {
        try {
            int totalDays = request.getDays();
            // Просить модель за один присест сгенерировать JSON на весь срок
            // (например, 60 дней) ненадёжно: локальная модель может зависать
            // на такой длине генерации или зацикливаться, не доходя до конца.
            // Поэтому режем на партии по 14 дней и склеиваем результат,
            // сдвигая номера дней в каждой партии на нужный оффсет.
            final int CHUNK_SIZE = 14;
            com.fasterxml.jackson.databind.node.ArrayNode combined = mapper.createArrayNode();

            for (int chunkStart = 1; chunkStart <= totalDays; chunkStart += CHUNK_SIZE) {
                int chunkDays = Math.min(CHUNK_SIZE, totalDays - chunkStart + 1);
                String chunkJson = generatePlanChunk(request.getGoal(), chunkDays, chunkStart, totalDays);
                JsonNode chunkArray = mapper.readTree(chunkJson);
                for (JsonNode item : chunkArray) {
                    combined.add(item);
                }
            }

            return ResponseEntity.ok(mapper.writeValueAsString(combined));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

    // Генерирует один "чанк" плана (до 14 дней) с явным указанием, какой это
    // диапазон дней внутри всего срока — чтобы модель понимала общий контекст
    // (например, что день 15 идёт после дня 14 из предыдущей партии) и не
    // теряла логику прогрессии сложности между чанками.
    private String generatePlanChunk(String goal, int chunkDays, int chunkStart, int totalDays) throws Exception {
        int chunkEnd = chunkStart + chunkDays - 1;
        String userPrompt = "Цель: " + goal + ". Общий срок всего плана: " + totalDays + " дней. " +
            "Сейчас сгенерируй ТОЛЬКО дни с " + chunkStart + " по " + chunkEnd + " (это часть большего плана, " +
            "не весь план целиком) — используй в поле \"day\" именно эти номера (" + chunkStart + "-" + chunkEnd + "), " +
            "с логичной прогрессией сложности, как будто это продолжение плана после предыдущих дней.";
        String reply = callOllama(PLAN_SYSTEM_PROMPT, userPrompt, 0.15, java.util.Collections.emptyList(), null, null);
        return extractJsonArray(reply);
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

        // Модель иногда вместо одного массива возвращает несколько отдельных
        // массивов через запятую: "[...],\n[...],\n[...]" — это не валидный
        // JSON (несколько top-level значений без общей обёртки).
        //
        // ВАЖНО: mapper.readTree(String) НЕ бросает исключение в этом случае —
        // Jackson по умолчанию парсит только первый JSON-документ из строки
        // и молча игнорирует всё, что идёт после него. Поэтому полагаться на
        // try/catch вокруг readTree бесполезно: readTree "успешно" вернёт
        // первый массив, а на фронт при этом уйдёт нетронутая грязная строка
        // целиком, и JSON.parse на клиенте (уже строгий) упадёт.
        //
        // Поэтому склеиваем ВСЕГДА, до парсинга — на валидный одиночный
        // массив эта операция не влияет (см. mergeMultipleArrays).
        String merged = mergeMultipleArrays(candidate);

        JsonNode parsed;
        try {
            parsed = mapper.readTree(merged);
        } catch (Exception e) {
            throw new IllegalStateException("Модель вернула невалидный JSON. Ответ: " + truncate(raw));
        }

        if (!parsed.isArray() || parsed.size() == 0) {
            throw new IllegalStateException("JSON-массив пустой или некорректный. Ответ: " + truncate(raw));
        }

        // Модель иногда возвращает category не в том регистре, с пробелами,
        // на русском или вообще что-то постороннее. Нормализуем каждый элемент,
        // чтобы на фронт и дальше в БД (day_tasks.category) всегда уходило
        // ровно одно из трёх ожидаемых значений: tasks / goals / leisure.
        for (JsonNode item : parsed) {
            if (item.isObject() && item instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                com.fasterxml.jackson.databind.node.ObjectNode obj =
                    (com.fasterxml.jackson.databind.node.ObjectNode) item;
                String rawCategory = obj.has("category") ? obj.get("category").asText("") : "";
                obj.put("category", normalizeCategory(rawCategory));
            }
        }

        return mapper.writeValueAsString(parsed);
    }

    /**
     * Склеивает несколько top-level JSON-массивов, разделённых запятой
     * (и опционально пробелами/переносами строк), в один валидный массив.
     * "[1,2],\n[3,4]" -> "[1,2,3,4]"
     * Для уже валидного одиночного массива операция безопасна (no-op):
     * снимает только внешние крайние скобки и сразу оборачивает обратно.
     */
    private String mergeMultipleArrays(String candidate) {
        String trimmed = candidate.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Между массивами могла остаться граница вида "],\s*[" — превращаем её
        // в простую запятую, объединяя содержимое всех массивов в один.
        String joined = trimmed.replaceAll("\\]\\s*,\\s*\\[", ",");
        return "[" + joined + "]";
    }

    private String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // Приводит присланное моделью значение категории к одному из трёх
    // допустимых: tasks / goals / leisure. Любое незнакомое значение
    // (опечатка, другой регистр, слово на русском, отсутствие поля)
    // по умолчанию считаем "goals" — план в целом посвящён личной цели.
    private String normalizeCategory(String raw) {
        if (raw == null) return "goals";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "tasks": case "task": case "задачи": case "задача":
                return "tasks";
            case "goals": case "goal": case "личные цели": case "цель": case "цели":
                return "goals";
            case "leisure": case "досуг": case "отдых":
                return "leisure";
            default:
                return "goals";
        }
    }

    // Даёт модели не только "сегодняшнюю дату", но и явную таблицу
    // ближайших 14 дней с числом и днём недели. Без этого модель (особенно
    // локальная qwen2.5) плохо сопоставляет "воскресенье"/"послезавтра" с
    // конкретным числом и путается в порядке дней — а явный список снимает
    // необходимость считать в уме.
    private String getCurrentDateContext() {
        LocalDate today = LocalDate.now();
        DateTimeFormatter fullFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'года', EEEE", new Locale("ru"));
        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"));
        String formatted = today.format(fullFormatter);

        StringBuilder sb = new StringBuilder();
        sb.append("Сегодняшняя дата: ").append(formatted).append(".\n");
        sb.append("Календарь ближайших дней (используй это для точного сопоставления ")
          .append("названий дней недели и относительных слов вроде \"завтра\", \"послезавтра\", ")
          .append("\"на выходных\", \"на этой неделе\", \"на следующей неделе\" с конкретной датой; ")
          .append("не вычисляй даты в уме — бери готовое значение отсюда):\n");
        for (int i = 0; i <= 13; i++) {
            LocalDate d = today.plusDays(i);
            String tag;
            if (i == 0) tag = "сегодня";
            else if (i == 1) tag = "завтра";
            else if (i == 2) tag = "послезавтра";
            else tag = "через " + i + " дн.";
            sb.append("- ").append(d.format(dayFormatter))
              .append(" — ").append(capitalize(d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, new Locale("ru"))))
              .append(" (").append(tag).append(")\n");
        }
        return sb.toString().trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Возвращает базовые заголовки с Bearer-токеном Groq — общие для всех
    // запросов к их API.
    private HttpHeaders groqHeaders() {
        if (GROQ_API_KEY == null || GROQ_API_KEY.isBlank()) {
            throw new IllegalStateException(
                "GROQ_API_KEY не задан. Установите переменную окружения GROQ_API_KEY " +
                "(ключ из https://console.groq.com/keys) на сервере, где запущен бэкенд.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + GROQ_API_KEY);
        return headers;
    }

    // Раньше это был вызов Ollama /api/generate (одна плоская строка prompt,
    // без понятия "чат-сообщений"). Groq — полностью OpenAI-совместимый API,
    // умеющий только chat completions, поэтому системный промпт и запрос
    // пользователя собираются в messages: [{role:system}, {role:user}],
    // а для изображений content становится массивом {type:text}/{type:image_url}
    // вместо отдельного поля "images", как было у Ollama.
    private String callOllama(
        String systemPrompt,
        String userMessage,
        double temperature,
        java.util.List<String> images,
        String languageReminder,
        String historyText
    ) throws Exception {
        String dateContext = getCurrentDateContext();
        boolean hasImages = images != null && !images.isEmpty();

        StringBuilder sysContent = new StringBuilder();
        sysContent.append(systemPrompt).append("\n\n").append(dateContext);
        if (historyText != null && !historyText.isBlank()) {
            sysContent.append("\n\nИстория переписки в этом чате (для контекста, не переспрашивай то, что уже обсуждалось):\n\n")
                .append(historyText);
        }
        if (languageReminder != null) {
            sysContent.append("\n\n[Языковое правило]: ").append(languageReminder);
        }

        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", sysContent.toString());

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        if (hasImages) {
            // Мультимодальный content: текст + картинки как data-URL.
            // Считаем jpeg по умолчанию (телефонные фото/скриншоты) — если
            // реальный формат другой, современные vision-модели обычно всё
            // равно корректно распознают контейнер по самим байтам.
            com.fasterxml.jackson.databind.node.ArrayNode contentArray = mapper.createArrayNode();
            ObjectNode textPart = mapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", userMessage);
            contentArray.add(textPart);
            for (String base64 : images) {
                ObjectNode imagePart = mapper.createObjectNode();
                imagePart.put("type", "image_url");
                ObjectNode imageUrl = mapper.createObjectNode();
                String url = base64.startsWith("data:") ? base64 : "data:image/jpeg;base64," + base64;
                imageUrl.put("url", url);
                imagePart.set("image_url", imageUrl);
                contentArray.add(imagePart);
            }
            userMsg.set("content", contentArray);
        } else {
            userMsg.put("content", userMessage);
        }

        com.fasterxml.jackson.databind.node.ArrayNode messages = mapper.createArrayNode();
        messages.add(systemMsg);
        messages.add(userMsg);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", hasImages ? VISION_MODEL_NAME : MODEL_NAME);
        body.set("messages", messages);
        body.put("temperature", temperature);
        body.put("stream", false);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), groqHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
            GROQ_URL,
            HttpMethod.POST,
            entity,
            String.class
        );

        JsonNode json = mapper.readTree(response.getBody());
        JsonNode choices = json.get("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("Groq не вернул ни одного варианта ответа");
        }
        JsonNode message = choices.get(0).get("message");
        return message != null && message.has("content") ? message.get("content").asText("") : "";
    }
}