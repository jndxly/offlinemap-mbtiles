package com.example.mbtiles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 每个 .mbtiles 的连接池由 {@link com.example.mbtiles.service.MbtilesRegistry} 自行创建
 * （jdbc:sqlite:file:/…?mode=ro），不注册为 Spring Bean，
 * 因此排除 Boot 的 DataSource 自动配置，否则会因缺少 spring.datasource.url 启动失败。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class MbtilesTileServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbtilesTileServerApplication.class, args);
    }
}
