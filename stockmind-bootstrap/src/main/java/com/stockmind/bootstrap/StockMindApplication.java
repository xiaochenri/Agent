package com.stockmind.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication(scanBasePackages = {"com.stockmind", "com.agent"})
@ComponentScan(
        basePackages = {"com.stockmind", "com.agent"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.stockmind\\.javascope\\..*"))
public class StockMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockMindApplication.class, args);
    }
}
