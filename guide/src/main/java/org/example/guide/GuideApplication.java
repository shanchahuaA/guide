package org.example.guide;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@MapperScan("org.example.guide.mapper")
@SpringBootApplication
public class GuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuideApplication.class, args);
    }

}
