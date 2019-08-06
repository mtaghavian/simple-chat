package simplechat.config;

import simplechat.util.ByteUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import simplechat.Repository.SessionRepository;
import simplechat.Repository.UserRepository;
import simplechat.SimpleChatApplication;
import simplechat.model.Session;
import simplechat.model.User;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.*;

@Component
public class HttpInterceptor implements HandlerInterceptor {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ByteUtils byteUtils;

    private Set<String> allowedUrls = new HashSet<>();
    private Set<String> allowedFiles = new HashSet<>();
    private Set<String> htmlFiles = new HashSet<>();

    public HttpInterceptor() {
        allowedUrls.add("/");
        allowedUrls.add("/home");
        allowedUrls.add("/index");
        allowedUrls.add("/logout");
        allowedUrls.add("/login-helper");
        allowedUrls.add("/signup");
        allowedUrls.add("/signup-helper");

        for (String filename : Objects.requireNonNull(new File(SimpleChatApplication.imageResourcePath).list())) {
            allowedFiles.add("/" + filename);
        }
        for (String filename : Objects.requireNonNull(new File(SimpleChatApplication.styleResourcePath).list())) {
            allowedFiles.add("/" + filename);
        }
        for (String filename : Objects.requireNonNull(new File(SimpleChatApplication.scriptResourcePath).list())) {
            allowedFiles.add("/" + filename);
        }
        for (String filename : Objects.requireNonNull(new File(SimpleChatApplication.pageResourcePath).list())) {
            htmlFiles.add("/" + filename);
        }
    }

    private void putHeaderParams(Map<String, String> params, User user) {
        if (user != null) {
            params.put("headerAccountText", user.getFirstname() + " " + user.getLastname());
            params.put("headerAccountAction", "");
            params.put("headerAccountClasses", "Dropdown");
            params.put("HeaderUserCaretSvgDisplay", "inline-block");
        } else {
            params.put("headerAccountText", "Sign In / Sign Up");
            params.put("headerAccountAction", "onclick='location.href=\"/user\"'");
            params.put("headerAccountClasses", "");
            params.put("HeaderUserCaretSvgDisplay", "none");
        }
    }

    private void loginWithCookies(HttpServletRequest request, HttpSession httpSession, Session session) {
        if (request.getCookies() != null) {
            Map<String, String> cookies = new HashMap<>();
            for (Cookie c : request.getCookies()) {
                cookies.put(c.getName(), c.getValue());
            }
            if (cookies.containsKey("username") && cookies.containsKey("password")) {
                User dbUser = userRepository.findByUsername(cookies.get("username"));
                if ((dbUser != null) && dbUser.getPassword().equals(cookies.get("password"))) {
                    session.setUser(dbUser);
                }
            }
        }
    }

    private void loginWithBasicAuth(HttpServletRequest request, HttpSession httpSession, Session session) throws UnsupportedEncodingException {
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            String[] split = auth.split(" ");
            if ("basic".equals(split[0].toLowerCase())) {
                String base64 = split[1];
                String cred = new String(Base64.getDecoder().decode(base64), "UTF-8");
                String username = cred.substring(0, cred.indexOf(":"));
                String password = cred.substring(cred.indexOf(":") + 1, cred.length());
                User dbUser = userRepository.findByUsername(username);
                if ((dbUser != null) && dbUser.getPassword().equals(byteUtils.hash(password))) {
                    session.setUser(dbUser);
                }
            }
        }
    }

    @Scheduled(fixedRate = 3600000, initialDelay = 3600000)
    public void clearExpiredSessions() {
        List<Session> expired = sessionRepository.findExpired(System.currentTimeMillis() - 3600000);
        sessionRepository.deleteAll(expired);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        HttpSession httpSession = request.getSession();

        Session session = sessionRepository.findById(httpSession.getId()).orElse(null);
        if (session == null) {
            session = new Session(httpSession.getId(), null, System.currentTimeMillis());
        }
        session.setLastModified(System.currentTimeMillis());
        sessionRepository.save(session);

        loginWithBasicAuth(request, httpSession, session);
        loginWithCookies(request, httpSession, session);

        if (allowedFiles.contains(uri)) {
            String uriLC = uri.toLowerCase();
            if (uriLC.endsWith(".svg")) {
                response.setContentType("image/svg+xml");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri))));
            } else if (uriLC.endsWith(".css")) {
                response.setContentType("text/css");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.styleResourcePath + "/" + uri))));
            } else if (uriLC.endsWith(".js")) {
                response.setContentType("text/javascript");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.scriptResourcePath + "/" + uri))));
            } else if (uriLC.endsWith(".png")) {
                response.setContentType("image/png");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri))));
            } else if (uriLC.endsWith(".ico")) {
                response.setContentType("image/x-icon");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri))));
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return false;
        }

        if (!allowedUrls.contains(uri)) {
            if ("/login".equals(uri)) {
                if (session.getUser() != null) {
                    response.sendRedirect("/user");
                    return false;
                }
            } else {
                if (session.getUser() == null) {
                    session.setRedirectedUri(uri);
                    response.sendRedirect("/login");
                    return false;
                }
            }
        }

        if (uri.equals("/")) {
            uri = "/home";
        }
        if (htmlFiles.contains(uri + ".html")) {
            Map<String, String> params = new HashMap<>();
            putHeaderParams(params, session.getUser());
            params.put("pageTitle", uri.substring(1));
            String html = byteUtils.readPage(uri + ".html", params);
            response.setContentType("text/html");
            response.getOutputStream().write(html.getBytes("UTF-8"));
            return false;
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) throws Exception {
    }
}
