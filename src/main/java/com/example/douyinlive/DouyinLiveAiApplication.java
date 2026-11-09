package com.example.douyinlive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.douyinlive.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
// @Scheduled 用于限流清理、观众记忆定时落盘
@EnableScheduling
public class DouyinLiveAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DouyinLiveAiApplication.class, args);
    }
}
