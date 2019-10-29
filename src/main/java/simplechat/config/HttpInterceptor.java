package simplechat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import simplechat.SimpleChatApplication;
import java.net.URI;
import java.net.URISyntaxException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.*;

@Component
public class HttpInterceptor implements HandlerInterceptor {

    public HttpInterceptor() {
    }

    @Scheduled(fixedRate = 300000, initialDelay = 300000)
    public void pingGoogleToKeepServiceAlive() {
        ping("https://mtaghavian-simplechat.herokuapp.com/");
    }

    public void ping(String uriStr){
        try {
            RestTemplate restTemplate = new RestTemplate();
            URI uri = new URI(uriStr);
            restTemplate.getForEntity(uri, String.class);
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        HttpSession httpSession = request.getSession();
        response.setContentType("text/html");
        response.getOutputStream().write("This is a test".getBytes());
        return false;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) throws Exception {
    }
}
