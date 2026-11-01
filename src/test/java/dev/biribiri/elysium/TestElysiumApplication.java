package dev.biribiri.elysium;

import org.springframework.boot.SpringApplication;

public class TestElysiumApplication {

    static void main(String[] args) {
        SpringApplication.from(ElysiumApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
