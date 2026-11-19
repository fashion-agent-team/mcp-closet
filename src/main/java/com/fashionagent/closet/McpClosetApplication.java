package com.fashionagent.closet;

import java.time.Clock;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpClosetApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClosetApplication.class, args);
    }

    /** {@link ClosetTools}의 @Tool 메서드를 MCP 도구로 내놓는다. */
    @Bean
    ToolCallbackProvider closetToolCallbacks(ClosetTools closetTools) {
        return MethodToolCallbackProvider.builder().toolObjects(closetTools).build();
    }

    /** 오늘 날짜(입은 날 기본값) 계산용. 테스트에서 바꿔 끼운다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
