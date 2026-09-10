package io.github.lu1j.rolloutcore.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("io.github.lu1j.rolloutcore.server.mapper")
public class RolloutCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(RolloutCoreApplication.class, args);
    }
}
