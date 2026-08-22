package com.tencent.supersonic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Headless Launcher */
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.tencent.supersonic"},
        exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
@EnableScheduling
public class HeadlessLauncher {

    public static void main(String[] args) {
        SpringApplication.run(HeadlessLauncher.class, args);
    }
}
