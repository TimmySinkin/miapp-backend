package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

@Service
public class StorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final String publicEndpoint;

    public StorageService(
            @Value("${yandex.storage.endpoint}") String endpoint,
            @Value("${yandex.storage.region}") String region,
            @Value("${yandex.storage.bucket}") String bucket,
            @Value("${yandex.storage.access-key}") String accessKey,
            @Value("${yandex.storage.secret-key}") String secretKey
    ) {
        this.bucket = bucket;
        this.publicEndpoint = endpoint;
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    /**
     * Загружает файл в бакет под уникальным именем и возвращает публичный URL.
     * Уникальное имя — чтобы не перезаписывать чужой файл и не давать
     * пользователю угадывать/подменять чужие аватары по предсказуемому пути.
     */
    public String uploadAvatar(MultipartFile file, String login) throws Exception {
        String extension = extractExtension(file.getOriginalFilename());
        String key = "avatars/" + login + "-" + UUID.randomUUID() + extension;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
        );

        return publicEndpoint + "/" + bucket + "/" + key;
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
