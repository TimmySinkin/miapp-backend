package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Проверка Google access_token у самого Google (а не доверие данным с фронта).
 * Вынесено из OAuthController, чтобы этой же проверкой мог пользоваться
 * AccountLinkController при привязке Google к уже существующему аккаунту —
 * логика проверки токена одна и та же, разница только в том, что делать
 * с профилем после проверки (логинить нового/старого юзера, или дописывать
 * google_id уже залогиненному).
 */
@Service
public class GoogleAuthService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode verifyGoogleToken(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> resp = restTemplate.exchange(
            "https://www.googleapis.com/oauth2/v3/userinfo",
            HttpMethod.GET, entity, String.class
        );
        return mapper.readTree(resp.getBody());
    }
}
