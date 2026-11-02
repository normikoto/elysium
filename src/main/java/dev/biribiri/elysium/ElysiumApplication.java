package dev.biribiri.elysium;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ElysiumApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElysiumApplication.class, args);
    }

}
