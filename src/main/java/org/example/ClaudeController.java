package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    // границы: 10 сек на подключение, 2 минуты на чтение (YandexGPT работает быстро,
    // это далеко не Ollama, но запас на сеть/ретраи не помешает).
    private final RestTemplate restTemplate = buildRestTemplateWithTimeouts();

    private static RestTemplate buildRestTemplateWithTimeouts() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(2 * 60_000);
        return new RestTemplate(factory);
    }
    private final ObjectMapper mapper = new ObjectMapper();

    // YandexGPT — OpenAI-совместимый endpoint Yandex AI Studio. Переехали сюда
    // с Groq, потому что Groq геоблокирует запросы с российских IP (санкционная
    // политика OFAC) — сервер сейчас физически в России (Beget), и без VPN
    // Groq отвечал 403 Forbidden. YandexGPT работает с российских IP нативно.
    //
    // Нужны ДВЕ переменные окружения (обе обязательны):
    // - YANDEX_AI_API_KEY   — API-ключ сервисного аккаунта с ролью ai.languageModels.user
    //                         (создаётся в Yandex Cloud Console -> Service accounts -> API keys)
    // - YANDEX_AI_FOLDER_ID — ID каталога (folder) в Yandex Cloud, куда привязан ключ
    //                         (виден в консоли рядом с названием каталога)
    private static final String YANDEX_AI_API_KEY = System.getenv("YANDEX_AI_API_KEY");
    private static final String YANDEX_AI_FOLDER_ID = System.getenv("YANDEX_AI_FOLDER_ID");
    private static final String YANDEX_URL = "https://ai.api.cloud.yandex.net/v1/chat/completions";

    // Модель задаётся URI вида gpt://<folder_id>/<model>/<версия>.
    // "rc" — release candidate ветка (обычно свежее и умнее "latest",
    // рекомендована Yandex для новых интеграций на момент миграции).
    // Собирается лениво (folder id известен только в рантайме из env).
    private static String modelUri(String modelName) {
        return "gpt://" + YANDEX_AI_FOLDER_ID + "/" + modelName + "/rc";
    }
    private static final String MODEL_NAME_BASE = "yandexgpt";

    // --- Groq: вторичный провайдер -------------------------------------------------
    // Yandex остаётся ОСНОВНЫМ провайдером — он стабильно работает с российских IP
    // без VPN и без риска гео-блокировки по OFAC. Groq подключается только как:
    //   (а) запасной путь, если Yandex внезапно недоступен (авария в облаке и т.п.);
    //   (б) опциональный "рецензент" в ensemble-режиме (см. chat()).
    // Именно поэтому, в отличие от истории миграции выше, здесь НЕ Groq -> Yandex,
    // а Yandex -> Groq: рискованный обходной путь держим вторичным, а не основным.
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    // Groq геоблокирует российские IP (403), поэтому запросы к нему могут идти
    // через прокси/VPN с выходом за пределами РФ. Прокси настраивается
    // ТОЛЬКО для Groq — остальной трафик (Yandex, вебхуки и т.п.) прокси не трогает.
    // Если GROQ_PROXY_HOST не задан — запросы идут напрямую (пригодится, если
    // сервер когда-нибудь переедет туда, где Groq доступен без обхода).
    private final RestTemplate groqRestTemplate = buildGroqRestTemplate();

    // Webshare (и большинство подобных сервисов) выдают HTTP-прокси С АВТОРИЗАЦИЕЙ
    // (логин/пароль на самом прокси), а не анонимный SOCKS5. Стандартный
    // java.net.Proxy + SimpleClientHttpRequestFactory такую авторизацию сам не
    // подставляет (без глобального Authenticator, который задел бы вообще все
    // HTTP-соединения JVM, включая запросы к Yandex — так делать не стоит).
    // Поэтому именно для Groq используем Apache HttpClient5 с CredentialsProvider,
    // ограниченным только этим прокси-хостом — остальной трафик его не видит.
    private static RestTemplate buildGroqRestTemplate() {
        String proxyHost = System.getenv("GROQ_PROXY_HOST");
        String proxyPortEnv = System.getenv("GROQ_PROXY_PORT");
        String proxyUser = System.getenv("GROQ_PROXY_USER");
        String proxyPass = System.getenv("GROQ_PROXY_PASS");

        var factory = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(8_000);
        // HttpComponentsClientHttpRequestFactory не имеет отдельного read-timeout
        // сеттера в этой версии Spring — таймаут на чтение ответа задаётся через
        // requestConfig ниже (responseTimeout), настройки собраны в один клиент.

        if (proxyHost != null && !proxyHost.isBlank() && proxyPortEnv != null && !proxyPortEnv.isBlank()) {
            int proxyPort = Integer.parseInt(proxyPortEnv.trim());
            var proxyHttpHost = new org.apache.hc.core5.http.HttpHost("http", proxyHost, proxyPort);

            var credsProvider = new org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider();
            if (proxyUser != null && !proxyUser.isBlank()) {
                credsProvider.setCredentials(
                    new org.apache.hc.client5.http.auth.AuthScope(proxyHttpHost),
                    new org.apache.hc.client5.http.auth.UsernamePasswordCredentials(
                        proxyUser, proxyPass == null ? new char[0] : proxyPass.toCharArray())
                );
            }

            var requestConfig = org.apache.hc.client5.http.config.RequestConfig.custom()
                .setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(8))
                .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(60))
                .build();

            var httpClient = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                .setProxy(proxyHttpHost)
                .setDefaultCredentialsProvider(credsProvider)
                .setDefaultRequestConfig(requestConfig)
                .build();

            factory.setHttpClient(httpClient);
        }
        // Если GROQ_PROXY_HOST не задан — factory работает без прокси (прямое
        // подключение), пригодится, если сервер когда-нибудь переедет туда,
        // где Groq доступен без обхода.

        return new RestTemplate(factory);
    }

    // Небольшой контракт, за которым скрывается разница между провайдерами:
    // URL, формат заголовков авторизации и то, каким RestTemplate слать запрос
    // (у Groq — с прокси/своими таймаутами). Сама логика tool-calling цикла
    // в callOllamaChatWithTools от конкретного провайдера не зависит.
    private interface AiClient {
        String name();
        String endpointUrl();
        String modelId();
        HttpHeaders headers();
        RestTemplate restTemplate();
    }

    private final AiClient yandexClient = new AiClient() {
        public String name() { return "yandex"; }
        public String endpointUrl() { return YANDEX_URL; }
        public String modelId() { return modelUri(MODEL_NAME_BASE); }
        public HttpHeaders headers() { return yandexHeaders(); }
        public RestTemplate restTemplate() { return restTemplate; }
    };

    private final AiClient groqClient = new AiClient() {
        public String name() { return "groq"; }
        public String endpointUrl() { return GROQ_URL; }
        public String modelId() { return GROQ_MODEL; }
        public HttpHeaders headers() {
            if (GROQ_API_KEY == null || GROQ_API_KEY.isBlank()) {
                throw new IllegalStateException(
                    "GROQ_API_KEY не задан — Groq недоступен как резервный провайдер.");
            }
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("Authorization", "Bearer " + GROQ_API_KEY);
            return h;
        }
        public RestTemplate restTemplate() { return groqRestTemplate; }
    };

    // Простейший circuit breaker без внешних зависимостей: если Yandex подряд
    // несколько раз падает, следующие N секунд сразу идём в Groq, не тратя
    // время на заведомо провальную попытку и ожидание таймаута каждый раз.
    private final AtomicInteger yandexFailStreak = new AtomicInteger(0);
    private volatile long yandexRetryAfterMs = 0;
    private static final int FAIL_STREAK_THRESHOLD = 3;
    private static final long BREAKER_COOLDOWN_MS = 30_000;
    // Явного подтверждения поддержки vision (картинок) через OpenAI-совместимый
    // endpoint YandexGPT на момент миграции найдено не было — используем ту же
    // текстовую модель для vision-запросов как временное решение. Если Yandex
    // не примет image_url в content, здесь нужно будет вернуть отдельную
    // vision-модель, когда Yandex явно её анонсирует (или переключить этот путь
    // обратно на Groq / другого провайдера только для запросов с картинками).
    private static final String VISION_MODEL_NAME_BASE = "yandexgpt";

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
        "Если для точного совета не хватает данных — уточни ИСКЛЮЧИТЕЛЬНО в формате блока " +
        "<<<CLARIFY>>>, описанного ниже в этом промпте. Никогда не задавай уточняющий вопрос " +
        "обычным текстом вне этого формата — иначе пользователь не сможет ответить кликом на кнопку. " +
        "Если цель требует регулярных действий — СРАЗУ включи в ответ конкретную таблицу " +
        "с примерным планом на первую неделю (день / действие / показатель), не спрашивая " +
        "предварительно разрешения на это и не ограничиваясь общими рекомендациями без цифр. " +
        "В конце можешь дополнительно предложить составить полный план на весь срок для сохранения в календарь.\n\n" +
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
        "из собственной памяти — это то же самое, что придумать факты, и запрещено без исключений.\n\n" +
        "УТОЧНЯЮЩИЕ ВОПРОСЫ ПЕРЕД РЕКОМЕНДАЦИЕЙ:\n" +
        "Если пользователь просит совет/рекомендацию (фильм, книга, подарок, рецепт, куда пойти и т.п.), " +
        "и без дополнительного контекста твой ответ будет слишком общим или может не подойти именно " +
        "этому человеку — сначала задай 1–2 КОРОТКИХ уточняющих вопроса с вариантами ответа, " +
        "и только затем давай финальную рекомендацию.\n" +
        "НЕ уточняй, если: запрос уже достаточно конкретный (например, «посоветуй фильм ужасов 2020-х»), " +
        "это фактический вопрос без элемента личного выбора («сколько калорий в яблоке»), " +
        "или это цель/план с достижением результата за срок (для них другой сценарий, уточнения не нужны).\n" +
        "Максимум ОДИН раунд уточнений за диалог — если пользователь уже ответил на уточняющие вопросы " +
        "(или это видно из истории переписки), сразу давай финальный ответ, не спрашивай снова.\n" +
        "ЕСЛИ ПОЛЬЗОВАТЕЛЬ ПРОПУСТИЛ УТОЧНЕНИЕ (кнопка «Пропустить», сообщение начинается со слов " +
        "«Пропускаю уточнение» и просит выбрать самому): НЕ сужай ответ до одного варианта. " +
        "Возьми предложенные тобою ранее варианты ответа (категории/жанры и т.п.) и по каждому дай " +
        "небольшую подборку (обычно 2–3 штуки на категорию), чтобы пользователь мог выбрать сам из " +
        "разнообразного набора. Если исходный запрос был не про личные предпочтения, а фактический/" +
        "поисковый (например, «что нового вышло», «что почитать про X») — просто дай содержательный " +
        "ответ по существу на основе доступной информации (в т.ч. вызови web_search, если нужны " +
        "актуальные данные), а не переспрашивай снова.\n" +
        "Формат уточняющего вопроса — ЕДИНСТВЕННО ДОПУСТИМЫЙ, когда решаешь уточнить: ответь СТРОГО так, " +
        "без единого слова до или после этого блока (это распарсит бэкенд, не пользователь):\n" +
        "<<<CLARIFY>>>\n" +
        "{\"text\": \"Короткая дружелюбная фраза о том, что ты уточняешь детали (1 предложение)\", " +
        "\"questions\": [{\"id\": \"короткий_id_на_латинице\", \"question\": \"Текст вопроса?\", " +
        "\"options\": [\"Вариант 1\", \"Вариант 2\", \"Вариант 3\"]}]}\n" +
        "<<<END>>>\n" +
        "Никогда не смешивай этот блок с обычным markdown-ответом в одном сообщении — либо ты " +
        "задаёшь уточняющий вопрос строго в этом формате и ничего больше, либо даёшь обычный " +
        "финальный ответ в markdown без этого блока вообще.\n\n" +
        "КРИТИЧЕСКИ ВАЖНОЕ ОГРАНИЧЕНИЕ ФОРМАТА ОТВЕТА (относится КО ВСЕМ твоим ответам, включая " +
        "финальные рекомендации, а не только к блоку <<<CLARIFY>>> выше): " +
        "твой ответ пользователю — это ВСЕГДА либо (а) чистый markdown-текст, либо (б) блок " +
        "<<<CLARIFY>>>...<<<END>>>, описанный выше, и НИКОГДА ничего третьего. " +
        "Категорически ЗАПРЕЩЕНО оборачивать финальный ответ в JSON-объект вида " +
        "{\"role\": \"assistant\", \"message\": \"...\"} или любую другую JSON-структуру с полями " +
        "role/message/content — это техническая обёртка API, которую видит только сервер, " +
        "человек должен увидеть просто обычный текст без фигурных скобок и кавычек вокруг всего " +
        "ответа. Если поймал себя на мысли обернуть ответ в {...} — это ошибка, немедленно " +
        "перепиши как обычный текст без обёртки.";

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
            // ВАЖНО: без явного .version(HTTP_1_1) Java HttpClient сам предлагает
            // HTTP/2 (см. ALPN offers h2,http/1.1 в TLS handshake) — а curl по
            // умолчанию использует HTTP/1.1. На практике DuckDuckGo отвечал Java-
            // клиенту статусом 202 и обычной главной страницой (антибот-заглушка),
            // хотя ИДЕНТИЧНЫЙ запрос через curl (HTTP/1.1) получал 200 и реальные
            // результаты. Похоже, DDG частично отличает автоматизированные
            // HTTP/2-клиенты. Форсируем HTTP/1.1 и добавляем типичные для браузера
            // заголовки (Accept, Accept-Language), чтобы запрос выглядел ближе
            // к curl/браузеру, а не к голому HttpClient по умолчанию.
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
            // ВАЖНО: DuckDuckGo Lite отдаёт реальные результаты только на POST
            // (см. <form action="/lite/" method="post"> в их собственной разметке).
            // GET-запрос с ?q=... в URL возвращает пустую страницу с одной лишь
            // формой поиска — никаких result-link/result-snippet там нет, поэтому
            // раньше поиск молча "не находил" вообще ничего, при любом запросе.
            String body = "q=" + java.net.URLEncoder.encode(query, "UTF-8");
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://lite.duckduckgo.com/lite/"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://lite.duckduckgo.com")
                .header("Referer", "https://lite.duckduckgo.com/lite/")
                .timeout(java.time.Duration.ofSeconds(6))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String html = resp.body();
            System.out.println("[webSearch] HTTP status=" + resp.statusCode() + " длина тела=" + html.length());
            System.out.println("[webSearch] превью тела: " +
                html.substring(0, Math.min(500, html.length())).replace("\n", " | "));

            // Грубый, но зависимостей не требующий парсинг HTML lite-версии DDG.
            // ВАЖНО: реальная разметка DDG отдаёт атрибуты в порядке
            // <a rel="nofollow" href="..." class='result-link'>Заголовок</a> —
            // то есть href идёт ПЕРЕД class, а не после, как можно было бы
            // наивно предположить. Прежний regex жёстко требовал class first,
            // href second — и поэтому не находил вообще ничего, хотя ссылки
            // реально были в ответе. Теперь сначала берём весь тег <a ...>...</a>
            // целиком, а атрибуты (class и href) вытаскиваем из него отдельно,
            // независимо от их взаимного порядка.
            java.util.regex.Matcher anchorMatcher = java.util.regex.Pattern
                .compile("<a\\s+([^>]*)>(.*?)</a>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
            java.util.regex.Pattern hrefPattern = java.util.regex.Pattern.compile("href=\"([^\"]+)\"");
            java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile("class=[\"']?result-link[\"']?");

            java.util.List<String> links = new java.util.ArrayList<>();
            java.util.List<String> titleTexts = new java.util.ArrayList<>();
            while (anchorMatcher.find() && links.size() < 6) {
                String attrs = anchorMatcher.group(1);
                if (!classPattern.matcher(attrs).find()) continue; // не result-link — пропускаем
                java.util.regex.Matcher hrefMatcher = hrefPattern.matcher(attrs);
                if (!hrefMatcher.find()) continue;
                links.add(hrefMatcher.group(1));
                titleTexts.add(stripHtmlTags(anchorMatcher.group(2)));
            }

            java.util.regex.Matcher snippetMatcher = java.util.regex.Pattern
                .compile("class=[\"']?result-snippet[\"']?[^>]*>(.*?)</td>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
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

    // Настоящий tool calling через YandexGPT /chat/completions (OpenAI-совместимый
    // формат) — раньше это был локальный Ollama /api/chat. Модель сама решает
    // по ходу диалога, нужен ли ей web_search, и может вызвать его несколько
    // раз подряд, прежде чем дать финальный текстовый ответ.
    private String callOllamaChatWithTools(
        AiClient client,
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
            body.put("model", client.modelId());
            body.put("stream", false);
            // Понижено с 0.7 до 0.4 — при более высокой температуре YandexGPT
            // чаще уходил в общие рекомендации без конкретики или спрашивал
            // разрешения составить план вместо того, чтобы сразу дать таблицу,
            // как того явно требует системный промпт (менее детерминированное
            // поведение при том же тексте инструкции).
            body.put("temperature", 0.4);
            // reasoning_effort убран: у qwen3 (none/default) и gpt-oss (low/medium/
            // high) разные допустимые значения этого параметра — жёстко заданное
            // значение подходило бы только одной модельной семье и рисковало бы
            // 400-ошибкой при следующей смене модели. Обе семьи разумно
            // рассуждают и без явного указания этого параметра.

            com.fasterxml.jackson.databind.node.ArrayNode messagesArray = mapper.createArrayNode();
            for (ObjectNode m : messages) messagesArray.add(m);
            body.set("messages", messagesArray);
            if (toolsArray != null) body.set("tools", toolsArray);

            HttpEntity<String> entity = new HttpEntity<>(body.toString(), client.headers());

            ResponseEntity<String> response = client.restTemplate().exchange(
                client.endpointUrl(),
                HttpMethod.POST,
                entity,
                String.class
            );

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode choices = json.get("choices");
            JsonNode message = (choices != null && choices.size() > 0) ? choices.get(0).get("message") : null;
            JsonNode toolCalls = message != null ? message.get("tool_calls") : null;

            if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0) {
                System.out.println("[" + client.name() + "] модель запросила " + toolCalls.size() + " вызов(ов) инструмента(ов), итерация " + iter);
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
                    // содержать tool_call_id с тем же значением — иначе Yandex
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

                    System.out.println("[tool_call] name=" + name + " query=\"" + query + "\"");
                    String result = "web_search".equals(name)
                        ? webSearch(query)
                        : "(неизвестный инструмент: " + name + ")";
                    System.out.println("[tool_result] длина=" + result.length() + " превью=" +
                        result.substring(0, Math.min(200, result.length())).replace("\n", " | "));

                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
                continue; // следующая итерация — модель увидит результаты инструментов
            }

            // Нет вызовов инструментов — это финальный текстовый ответ
            if (withTools) {
                System.out.println("[" + client.name() + "] модель НЕ вызвала ни одного инструмента, ответила сразу (итерация " + iter + ")");
            }
            return message != null && message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText("") : "";
        }

        return "Не удалось получить ответ после нескольких попыток вызова инструментов.";
    }

    // Сценарий 1 — failover: Yandex основной, Groq подключается только если
    // Yandex реально недоступен. Circuit breaker не даёт на каждый запрос
    // повторно ждать таймаут Yandex, если он уже несколько раз подряд упал —
    // следующие BREAKER_COOLDOWN_MS сразу идём в Groq.
    private String chatWithFailover(
        String systemPrompt, String userMessage, String historyText, boolean withTools
    ) throws Exception {
        boolean breakerOpen = System.currentTimeMillis() < yandexRetryAfterMs;
        if (!breakerOpen) {
            try {
                String result = callOllamaChatWithTools(yandexClient, systemPrompt, userMessage, historyText, withTools);
                yandexFailStreak.set(0);
                return result;
            } catch (Exception e) {
                int streak = yandexFailStreak.incrementAndGet();
                System.err.println("Yandex вызов #" + streak + " подряд неудачен: " + e.getMessage());
                if (streak >= FAIL_STREAK_THRESHOLD) {
                    yandexRetryAfterMs = System.currentTimeMillis() + BREAKER_COOLDOWN_MS;
                    System.err.println("Circuit breaker открыт на Yandex на " + BREAKER_COOLDOWN_MS + " мс, переключаемся на Groq");
                }
                // сразу пробуем Groq в этом же запросе, не заставляя пользователя ждать ретрая
            }
        }
        return callOllamaChatWithTools(groqClient, systemPrompt, userMessage, historyText, withTools);
    }

    // Сценарий 2 — ensemble ("черновик + рецензия"): Yandex быстро генерирует
    // черновик, Groq проверяет его на фактические/логические огрехи и может
    // подправить. Рецензия — best-effort: любая ошибка Groq (в т.ч. геоблок
    // без прокси) тихо откатывается на исходный черновик, а не роняет ответ.
    private String chatWithEnsemble(
        String systemPrompt, String userMessage, String historyText, boolean withTools
    ) throws Exception {
        String draft = callOllamaChatWithTools(yandexClient, systemPrompt, userMessage, historyText, withTools);
        try {
            String reviewPrompt =
                "Ниже вопрос пользователя и черновик ответа от другой модели. Проверь черновик на " +
                "фактические и логические ошибки. Если он в целом верный — верни его как есть, " +
                "лишь слегка улучшив формулировки. Если нашёл ошибку — исправь именно её, не переписывая " +
                "всё заново и не меняя структуру/объём без необходимости.\n\n" +
                "Вопрос пользователя: " + userMessage + "\n\nЧерновик ответа:\n" + draft;
            return callOllamaChatWithTools(groqClient, systemPrompt, reviewPrompt, null, false);
        } catch (Exception e) {
            System.err.println("Groq-рецензия недоступна (" + e.getMessage() + "), отдаём черновик от Yandex как есть");
            return draft;
        }
    }

    // Лёгкий health-check для Groq: реальный минимальный запрос через тот же
    // groqClient (и, если задан, тот же прокси), что используется в failover/
    // ensemble. Не трогает Yandex и не влияет на основной чат — только диагностика.
    // Пароль прокси в ответ не попадает, только сам факт "прокси настроен".
    @GetMapping("/groq-status")
    public ResponseEntity<String> groqStatus() {
        String proxyHost = System.getenv("GROQ_PROXY_HOST");
        boolean proxyConfigured = proxyHost != null && !proxyHost.isBlank();
        long start = System.currentTimeMillis();
        ObjectNode result = mapper.createObjectNode();
        result.put("provider", "groq");
        result.put("proxyConfigured", proxyConfigured);
        if (proxyConfigured) {
            result.put("proxyHost", proxyHost);
        }
        try {
            String reply = callOllamaChatWithTools(
                groqClient, "Отвечай одним словом, без пояснений.", "Скажи слово: ok", null, false
            );
            result.put("status", "ok");
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("sampleReply", reply.length() > 50 ? reply.substring(0, 50) : reply);
            return ResponseEntity.ok(result.toString());
        } catch (Exception e) {
            result.put("status", "down");
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("error", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(503).body(result.toString());
        }
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

            // ДЕТЕРМИНИРОВАННЫЙ запрет повторного раунда уточнений — не полагаемся
            // на то, что модель сама сосчитает, сколько раз уже спрашивала (на
            // практике она путается и может уточнять 3-4 раза подряд). Фронт
            // явно сообщает, был ли в этом чате уже clarify-раунд.
            if (request.isClarifyUsed()) {
                topicLock += "\n\nУТОЧНЯЮЩИЙ РАУНД УЖЕ БЫЛ ИСПОЛЬЗОВАН В ЭТОМ ЧАТЕ. " +
                    "Категорически ЗАПРЕЩЕНО использовать блок <<<CLARIFY>>> ещё раз или задавать " +
                    "любые новые уточняющие вопросы, даже если кажется, что не хватает деталей. " +
                    "Дай финальную рекомендацию ПРЯМО СЕЙЧАС, используя всё, что уже известно из " +
                    "истории переписки — при нехватке деталей просто выбери разумный вариант по умолчанию.";
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
                reply = chatWithFailover(systemPrompt, userMessage.toString(), historyText, false);
            } else {
                // Обычный текстовый сценарий (включая "фильмы") — с инструментом
                // web_search. Модель САМА решает, вызывать его или нет,
                // ориентируясь на описание инструмента и системный промпт,
                // а не по жёсткому списку ключевых слов в Java-коде.
                String systemPrompt = CHAT_SYSTEM_PROMPT.replace("%LANGUAGE_RULE%", languageRule) + topicLock;
                // ensembleMode — новое опциональное поле в ChatRequest (Boolean/boolean,
                // default false); фронт включает его отдельным тумблером "Точный режим",
                // т.к. это удваивает время ответа. Пока поле не добавлено на фронте/DTO,
                // request.isEnsembleMode() всегда вернёт false и поведение не меняется.
                reply = request.isEnsembleMode()
                    ? chatWithEnsemble(systemPrompt, userMessage.toString(), historyText, true)
                    : chatWithFailover(systemPrompt, userMessage.toString(), historyText, true);
            }

            return ResponseEntity.ok(wrapChatReply(reply));
        } catch (Exception e) {
            // Раньше исключение просто проглатывалось — в консоли бэкенда было
            // тихо даже при реальном 500. Теперь печатаем стек-трейс, иначе
            // единственный источник информации об ошибке — обрезанный текст
            // в теле ответа, который фронт вдобавок не всегда показывает.
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }

    // Превращает сырой ответ модели в JSON-контракт для фронта: либо
    // {"type":"answer","text":"..."} — обычный markdown-ответ как раньше,
    // либо {"type":"clarify","text":"...","questions":[...]} — если модель
    // решила сначала уточнить детали (см. блок <<<CLARIFY>>>...<<<END>>> в
    // системном промпте). Любая ошибка парсинга — безопасный откат на
    // обычный текстовый ответ, чтобы битый JSON от модели не ронял чат.
    private String wrapChatReply(String reply) {
        try {
            int start = reply.indexOf("<<<CLARIFY>>>");
            int end = reply.indexOf("<<<END>>>");
            if (start >= 0 && end > start) {
                String jsonPart = reply.substring(start + "<<<CLARIFY>>>".length(), end).trim();
                // На случай если модель всё же обернула JSON в ```-фенсы вопреки инструкции.
                jsonPart = jsonPart.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();

                JsonNode clarify;
                try {
                    clarify = mapper.readTree(jsonPart);
                } catch (Exception parseEx) {
                    // JSON внутри блока СИНТАКСИЧЕСКИ БИТЫЙ (например, модель забыла
                    // закрывающую скобку в массиве options — реально наблюдалось).
                    // Полноценно распарсить нельзя, но можно вытащить хотя бы
                    // вступительную фразу "text" через regex, не показывая
                    // пользователю сырой поломанный JSON целиком.
                    System.err.println("[wrapChatReply] битый JSON в блоке CLARIFY: " + parseEx.getMessage());
                    java.util.regex.Matcher textMatcher = java.util.regex.Pattern
                        .compile("\"text\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(jsonPart);
                    String before = reply.substring(0, start).trim();
                    String salvaged = !before.isEmpty() ? before
                        : (textMatcher.find() ? textMatcher.group(1) : null);
                    ObjectNode wrapped = mapper.createObjectNode();
                    wrapped.put("type", "answer");
                    wrapped.put("text", salvaged != null && !salvaged.isBlank()
                        ? salvaged
                        : "Не получилось сформулировать уточняющий вопрос — переформулируйте, пожалуйста, запрос чуть подробнее.");
                    return mapper.writeValueAsString(wrapped);
                }

                if (clarify.has("questions") && clarify.get("questions").isArray() && clarify.get("questions").size() > 0) {
                    ObjectNode wrapped = mapper.createObjectNode();
                    wrapped.put("type", "clarify");
                    wrapped.put("text", clarify.has("text") ? clarify.get("text").asText("") : "");
                    wrapped.set("questions", clarify.get("questions"));
                    return mapper.writeValueAsString(wrapped);
                }
                // Маркер есть, JSON валиден, но questions пустой — модель, похоже,
                // "запуталась" (например, начала имитировать несуществующий
                // tool-call текстом после <<<END>>>, как реально случалось).
                // КРИТИЧЕСКИ ВАЖНО: не откатываемся на весь "reply" целиком —
                // там может быть что угодно после маркера. Берём максимум
                // текст ДО начала блока <<<CLARIFY>>>, а если его нет — сам
                // clarify.text, а не сырой хвост ответа.
                String before = reply.substring(0, start).trim();
                String safeText = !before.isEmpty()
                    ? before
                    : (clarify.has("text") ? clarify.get("text").asText("") : "");
                if (!safeText.isBlank()) {
                    ObjectNode wrapped = mapper.createObjectNode();
                    wrapped.put("type", "answer");
                    wrapped.put("text", safeText);
                    return mapper.writeValueAsString(wrapped);
                }
            }
        } catch (Exception e) {
            System.err.println("[wrapChatReply] не удалось распарсить блок CLARIFY, откат на обычный ответ: " + e.getMessage());
        }

        // Защитный парсинг: модель иногда (несмотря на явный запрет в промпте)
        // оборачивает ВЕСЬ финальный ответ в фейковую JSON-обёртку вида
        // {"role": "assistant", "message": "..."} — реально наблюдалось на
        // практике. Если весь reply целиком выглядит как JSON-объект с одним
        // из типичных полей текста — вытаскиваем из него настоящий текст,
        // вместо того чтобы показать пользователю сырые фигурные скобки и кавычки.
        String trimmed = reply.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode leaked = mapper.readTree(trimmed);
                for (String field : new String[]{"message", "content", "text", "answer"}) {
                    if (leaked.has(field) && leaked.get(field).isTextual()) {
                        ObjectNode wrapped = mapper.createObjectNode();
                        wrapped.put("type", "answer");
                        wrapped.put("text", leaked.get(field).asText(""));
                        System.err.println("[wrapChatReply] обнаружена и распакована фейковая JSON-обёртка ответа (поле \"" + field + "\")");
                        return mapper.writeValueAsString(wrapped);
                    }
                }
            } catch (Exception ignored) {
                // Не распарсилось как JSON — значит это просто обычный текст,
                // в котором совпадением встретились { и } на границах, ничего страшного.
            }
        }

        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("type", "answer");
        wrapped.put("text", reply);
        try {
            return mapper.writeValueAsString(wrapped);
        } catch (Exception e) {
            // Крайний случай — Jackson не смог сериализовать (не должно происходить
            // для простой строки), возвращаем совсем грубый, но валидный JSON вручную.
            return "{\"type\":\"answer\",\"text\":" + mapper.valueToTree(reply).toString() + "}";
        }
    }

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

    // Возвращает базовые заголовки для Yandex AI Studio. В отличие от Groq,
    // формат авторизации у Yandex — "Api-Key <ключ>", а не "Bearer <ключ>",
    // и дополнительно нужен заголовок с folder ID (каталогом), к которому
    // привязан ключ.
    private HttpHeaders yandexHeaders() {
        if (YANDEX_AI_API_KEY == null || YANDEX_AI_API_KEY.isBlank()) {
            throw new IllegalStateException(
                "YANDEX_AI_API_KEY не задан. Установите переменную окружения YANDEX_AI_API_KEY " +
                "(API-ключ сервисного аккаунта из Yandex Cloud Console) на сервере, где запущен бэкенд.");
        }
        if (YANDEX_AI_FOLDER_ID == null || YANDEX_AI_FOLDER_ID.isBlank()) {
            throw new IllegalStateException(
                "YANDEX_AI_FOLDER_ID не задан. Установите переменную окружения YANDEX_AI_FOLDER_ID " +
                "(ID каталога в Yandex Cloud, к которому привязан API-ключ) на сервере, где запущен бэкенд.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Api-Key " + YANDEX_AI_API_KEY);
        headers.set("x-folder-id", YANDEX_AI_FOLDER_ID);
        return headers;
    }

    // Раньше это был вызов Ollama /api/generate (одна плоская строка prompt,
    // без понятия "чат-сообщений"). YandexGPT — OpenAI-совместимый API,
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
        body.put("model", modelUri(hasImages ? VISION_MODEL_NAME_BASE : MODEL_NAME_BASE));
        body.set("messages", messages);
        body.put("temperature", temperature);
        body.put("stream", false);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), yandexHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
            YANDEX_URL,
            HttpMethod.POST,
            entity,
            String.class
        );

        JsonNode json = mapper.readTree(response.getBody());
        JsonNode choices = json.get("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("YandexGPT не вернул ни одного варианта ответа");
        }
        JsonNode message = choices.get(0).get("message");
        return message != null && message.has("content") ? message.get("content").asText("") : "";
    }
}