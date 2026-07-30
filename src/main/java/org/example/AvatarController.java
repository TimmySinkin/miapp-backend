package org.example;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/account")
public class AvatarController {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 МБ
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final StorageService storageService;

    public AvatarController(JdbcTemplate jdbc, JwtUtil jwtUtil, StorageService storageService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.storageService = storageService;
    }

    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestParam("avatar") MultipartFile file, HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Файл не передан");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body("Файл слишком большой (максимум 5 МБ)");
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
            return ResponseEntity.badRequest().body("Можно загрузить только изображение (jpeg, png, webp, gif)");
        }

        try {
            String avatarUrl = storageService.uploadAvatar(file, login);
            jdbc.update("UPDATE users SET avatar_url = ? WHERE login = ?", avatarUrl, login);
            return ResponseEntity.ok(Map.of("status", "ok", "avatar_url", avatarUrl));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Не удалось загрузить файл: " + e.getMessage());
        }
    }
}