package com.fashionagent.closet.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fashionagent.closet.ClosetProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 임베딩 모델 고르기와 모델 파일 받기(closet.embedding.*). 진짜 허깅페이스 대신 이 컴퓨터의 작은 HTTP 서버를 쓴다. */
class EmbeddingConfigTest {

    @TempDir
    Path dir;

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.onnx", ex -> {
            hits.incrementAndGet();
            byte[] body = "onnx-bytes".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    static ClosetProperties props(String provider, String cacheDir) {
        var e = new ClosetProperties.Embedding(provider, "unused", "unused", cacheDir, 256, Duration.ofSeconds(5));
        return new ClosetProperties(0.9, 20, 500, 8, 20, 3, e, null);
    }

    static ClosetProperties.Embedding embedding() {
        return props("onnx", "models").embedding();
    }

    @Test
    @DisplayName("provider=hash면 모델 없이 도는 hash 임베딩을 쓴다(대소문자 무시)")
    void hashProvider() throws Exception {
        assertThat(new EmbeddingConfig().embeddingModel(props("HASH", dir.toString())))
                .isInstanceOf(HashEmbeddingModel.class);
    }

    @Test
    @DisplayName("onnx·hash가 아닌 provider는 잘못된 설정이라고 알려 준다")
    void unknownProvider() {
        assertThatThrownBy(() -> new EmbeddingConfig().embeddingModel(props("openai", dir.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onnx 또는 hash")
                .hasMessageContaining("openai");
    }

    @Test
    @DisplayName("http 모델은 처음 한 번만 내려받고, 다음부터는 받아 둔 파일을 쓴다")
    void downloadsOnce() throws Exception {
        Path target = dir.resolve("cache/model.onnx");

        Path first = EmbeddingConfig.fetch(url("/model.onnx"), target, embedding());
        Path second = EmbeddingConfig.fetch(url("/model.onnx"), target, embedding());

        assertThat(first).isEqualTo(target);
        assertThat(second).isEqualTo(target);
        assertThat(Files.readString(target)).isEqualTo("onnx-bytes");
        assertThat(hits.get()).isEqualTo(1);
        assertThat(target.resolveSibling("model.onnx.part")).doesNotExist();
    }

    @Test
    @DisplayName("서버가 200이 아니면 받다 만 파일을 지우고 HTTP 코드를 알려 준다")
    void httpError() {
        Path target = dir.resolve("model.onnx");
        assertThatThrownBy(() -> EmbeddingConfig.fetch(url("/missing"), target, embedding()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(target).doesNotExist();
        assertThat(dir.resolve("model.onnx.part")).doesNotExist();
    }

    @Test
    @DisplayName("연결이 안 되면 어떤 파일을 확인할지 알려 준다")
    void connectionRefused() {
        server.stop(0);
        Path target = dir.resolve("model.onnx");
        assertThatThrownBy(() -> EmbeddingConfig.fetch(url("/model.onnx"), target, embedding()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("모델을 내려받지 못했어요")
                .hasMessageContaining(target.toString());
        assertThat(dir.resolve("model.onnx.part")).doesNotExist();
    }

    @Test
    @DisplayName("file: 경로는 복사하지 않고 그 파일을 그대로 쓴다")
    void localFile() throws Exception {
        Path local = Files.writeString(dir.resolve("my.onnx"), "local");
        Path used = EmbeddingConfig.fetch(local.toUri().toString(), dir.resolve("unused/model.onnx"), embedding());
        assertThat(used).isEqualTo(local);
        assertThat(dir.resolve("unused")).doesNotExist();
    }

    @Test
    @DisplayName("jar 안(classpath:) 파일은 cacheDir로 꺼내서 쓴다")
    void classpathInsideJar() throws Exception {
        Path target = dir.resolve("out/Test.class");
        Path used = EmbeddingConfig.fetch("classpath:org/junit/jupiter/api/Test.class", target, embedding());
        assertThat(used).isEqualTo(target);
        byte[] head = java.util.Arrays.copyOf(Files.readAllBytes(target), 4);
        assertThat(head).containsExactly(0xCA, 0xFE, 0xBA, 0xBE);
    }

    @Test
    @DisplayName("폴더로 풀린 classpath: 파일은 꺼내지 않고 원본을 쓴다")
    void classpathOnDisk() throws Exception {
        Path target = dir.resolve("out/casual.md");
        Path used = EmbeddingConfig.fetch("classpath:style_docs/casual.md", target, embedding());
        assertThat(used).isNotEqualTo(target);
        assertThat(Files.readString(used)).isNotBlank();
        assertThat(target).doesNotExist();
    }
}
