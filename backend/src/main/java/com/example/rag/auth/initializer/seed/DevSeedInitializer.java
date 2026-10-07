package com.example.rag.auth.initializer.seed;

import com.example.rag.auth.service.seed.DevSeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "rag.seed.enabled", havingValue = "true")
public class DevSeedInitializer implements ApplicationRunner {

    private static final String ADMIN_PASSWORD_ENV = "SEED_ADMIN_PASSWORD";
    private static final String ALPHA_PASSWORD_ENV = "SEED_ALPHA_PASSWORD";
    private static final String BETA_PASSWORD_ENV = "SEED_BETA_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(DevSeedInitializer.class);
    private final DevSeedService service;
    private final Environment environment;

    /** 配置开发种子服务和环境变量来源。 */
    public DevSeedInitializer(DevSeedService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    /** 仅在启用开发种子时读取环境密码并初始化演示账号。 */
    @Override
    public void run(ApplicationArguments args) {
        service.initialize(
            environment.getProperty(ADMIN_PASSWORD_ENV),
            environment.getProperty(ALPHA_PASSWORD_ENV),
            environment.getProperty(BETA_PASSWORD_ENV)
        );
        log.info("development seed ready, departments=2, users=3; existing passwords preserved");
    }
}
