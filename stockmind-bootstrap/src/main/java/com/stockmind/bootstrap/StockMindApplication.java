package com.stockmind.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.stockmind")
public class StockMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockMindApplication.class, args);
    }
}
