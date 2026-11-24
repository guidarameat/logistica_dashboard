package br.com.guidara.expedicao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExpedicaoDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpedicaoDashboardApplication.class, args);
    }
}
