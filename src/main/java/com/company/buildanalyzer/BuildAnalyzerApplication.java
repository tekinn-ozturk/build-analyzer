package com.company.buildanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BuildAnalyzerApplication {
//spring ayağa kaldırma noktası program.cs gibi düşünebiliriz.
    public static void main(String[] args) {
        SpringApplication.run(BuildAnalyzerApplication.class, args);
    }
}
