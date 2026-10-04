package dev.daybook.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DaybookApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(DaybookApiApplication.class, args);
  }
}
