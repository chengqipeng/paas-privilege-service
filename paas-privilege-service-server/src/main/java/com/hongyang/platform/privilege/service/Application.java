package com.hongyang.platform.privilege.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class Application {
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        org.springframework.context.ConfigurableApplicationContext ctx = SpringApplication.run(Application.class, args);
        String port = ctx.getEnvironment().getProperty("server.port", "unknown");
        long cost = System.currentTimeMillis() - startTime;
        System.out.println("\n========================================");
        System.out.println("  Application started successfully!");
        System.out.println("  Port: " + port);
        System.out.println("  Startup time: " + cost + "ms");
        System.out.println("========================================\n");
    }
}
