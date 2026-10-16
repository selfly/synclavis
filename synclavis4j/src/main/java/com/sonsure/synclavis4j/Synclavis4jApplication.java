package com.sonsure.synclavis4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * synclavis Java 后端启动类。
 *
 * <p>约束：JDK 8 + Spring Boot 2.7.18 + JGit 5.13.x，不得使用 Java 9+ 语法与 API。</p>
 */
@SpringBootApplication
public class Synclavis4jApplication {

    public static void main(String[] args) {
        SpringApplication.run(Synclavis4jApplication.class, args);
    }
}
