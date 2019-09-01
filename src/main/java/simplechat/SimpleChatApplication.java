package simplechat;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import simplechat.config.HttpInterceptor;
import simplechat.model.User;
import simplechat.repository.UserRepository;
import simplechat.util.ByteUtils;

import javax.annotation.PostConstruct;

@SpringBootApplication
@EnableScheduling
@EnableAutoConfiguration(exclude = ErrorMvcAutoConfiguration.class)
public class SimpleChatApplication implements ApplicationContextAware {

    public static final String pageResourcePath = "res/html";
    public static final String styleResourcePath = "res/css";
    public static final String scriptResourcePath = "res/js";
    public static final String imageResourcePath = "res/img";
    public static final String miscResourcePath = "res/misc";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ByteUtils byteUtils;

    private static ApplicationContext context;

    public static void main(String[] args) {
        SpringApplication.run(SimpleChatApplication.class, args);
    }

    @PostConstruct
    public void starter() {
        if (userRepository.findByUsername("admin") == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(byteUtils.hash("admin"));
            admin.setFirstname("Masoud");
            admin.setLastname("Taghavian");
            userRepository.save(admin);

            User user = new User();
            user.setUsername("user");
            user.setPassword(byteUtils.hash("user"));
            user.setFirstname("Fariba");
            user.setLastname("Ghaffari");
            userRepository.save(user);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext ac) throws BeansException {
        context = ac;
    }

    public static ApplicationContext getContext() {
        return context;
    }

    public static <T> T getBean(Class<T> requiredType) {
        return context.getBean(requiredType);
    }

    @Configuration
    public class InterceptorConfig extends WebMvcConfigurerAdapter {

        @Autowired
        HttpInterceptor serviceInterceptor;

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(serviceInterceptor);
        }
    }

    @Bean(name = "multipartResolver")
    public CommonsMultipartResolver multipartResolver() {
        CommonsMultipartResolver multipartResolver = new CommonsMultipartResolver();
        multipartResolver.setMaxUploadSize(10000000000l);
        return multipartResolver;
    }
}
