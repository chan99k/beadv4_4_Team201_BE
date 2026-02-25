package app.giftify.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import app.giftify.security.common.config.SharedSecurityAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = SharedSecurityAutoConfiguration.class, scanBasePackages = {
    "app.giftify.settlement",
    "app.giftify.shared",
    "app.giftify.security",
    "app.giftify.support.common.event",
    "app.giftify.support.jpa",
    "giftify.support.web.handler"
})
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = {
    "app.giftify.settlement"
})
@EnableScheduling
@EnableRetry
@EnableAsync
@ConfigurationPropertiesScan
public class SettlementServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementServerApplication.class, args);
    }
}
