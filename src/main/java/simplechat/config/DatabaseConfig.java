package simplechat.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

//@Configuration
public class DatabaseConfig {

    @Scheduled(fixedRate = 300000, initialDelay = 300000)
    public void pingEchoServiceToKeepServiceAlive() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            URI uri = new URI("https://mtaghavian-heroku-echo.herokuapp.com/");
            restTemplate.getForEntity(uri, String.class);
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }
    }

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        return new HikariDataSource(config);
    }
}
