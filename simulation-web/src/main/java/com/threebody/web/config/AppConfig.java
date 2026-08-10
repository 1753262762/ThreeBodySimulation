package com.threebody.web.config;

import com.threebody.app.service.ExperimentRepository;
import com.threebody.app.service.ExperimentService;
import com.threebody.app.service.persistence.FileExperimentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public ExperimentRepository experimentRepository() {
        return new FileExperimentRepository();
    }

    @Bean
    public ExperimentService experimentService(ExperimentRepository repository) {
        ExperimentService service = new ExperimentService(repository);
        service.initialize();
        return service;
    }
}
