package com.fashionagent.closet.embedding;

import com.fashionagent.closet.ClosetProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

/** closet.embedding.provider에 따라 임베딩 모델을 고른다. onnx면 모델 파일을 한 번 내려받아 cacheDir에 둔다. */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    EmbeddingModel embeddingModel(ClosetProperties props) throws Exception {
        var e = props.embedding();
        if ("hash".equalsIgnoreCase(e.provider())) {
            log.warn("임베딩: hash(개발용, 뜻을 모름). 운영에서는 CLOSET_EMBEDDING_PROVIDER=onnx");
            return new HashEmbeddingModel();
        }
        if (!"onnx".equalsIgnoreCase(e.provider())) {
            throw new IllegalStateException("closet.embedding.provider는 onnx 또는 hash여야 해요: " + e.provider());
        }
        Path dir = Path.of(e.cacheDir());
        Path model = fetch(e.modelUri(), dir.resolve("model.onnx"), e);
        Path tokenizer = fetch(e.tokenizerUri(), dir.resolve("tokenizer.json"), e);
        long started = System.nanoTime();
        var m = new E5OnnxEmbeddingModel(model, tokenizer, e.maxTokens());
        log.info("임베딩: multilingual-e5-small ONNX 준비 ({} ms)", (System.nanoTime() - started) / 1_000_000);
        return m;
    }

    /** http(s)는 처음 한 번만 내려받고(이미 있으면 그대로), file:·classpath:는 그 파일을 쓴다. */
    static Path fetch(String uri, Path target, ClosetProperties.Embedding e) throws IOException, InterruptedException {
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            var res = new DefaultResourceLoader().getResource(uri);
            if (res.isFile()) {
                return res.getFile().toPath();
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = res.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(target.getParent());
        log.info("모델 내려받는 중: {} → {}", uri, target);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build();
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(e.downloadTimeout()).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw new IOException("모델을 내려받지 못했어요(" + ex.getClass().getSimpleName() + "): " + uri
                    + ". 이미지에 모델이 들어 있으면 " + target + "를 읽을 수 있는지 보세요.", ex);
        }
        if (res.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("모델을 내려받지 못했어요(HTTP " + res.statusCode() + "): " + uri);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }
}
