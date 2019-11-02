package simplechat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import simplechat.SimpleChatApplication;
import simplechat.model.FileInfo;
import simplechat.model.Session;
import simplechat.model.User;
import simplechat.repository.FileInfoRepository;
import simplechat.repository.SessionRepository;
import simplechat.repository.UserRepository;
import simplechat.util.ByteUtils;

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

    @Autowired
    private FileInfoRepository fileInfoRepository;

    private Set<String> allowedUrls = new HashSet<>();
    private Set<String> allowedFiles = new HashSet<>();
    private Set<String> htmlFiles = new HashSet<>();

    public HttpInterceptor() {
        allowedUrls.add("/");
        allowedUrls.add("/home");
        allowedUrls.add("/index");
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
        for (String filename : Objects.requireNonNull(new File(SimpleChatApplication.miscResourcePath).list())) {
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

    private Properties getPropertiesFromCookies(Cookie cookie[]) {
        if (cookie == null) {
            return null;
        }
        Properties cookies = new Properties();
        for (Cookie c : cookie) {
            cookies.put(c.getName(), c.getValue());
        }
        return cookies;
    }

    public void loginWithCookies(Properties cookies, Session session) {
        if (cookies != null) {
            if (cookies.containsKey("username") && cookies.containsKey("password")) {
                User dbUser = userRepository.findByUsername("" + cookies.get("username"));
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
        loginWithCookies(getPropertiesFromCookies(request.getCookies()), session);

        if (allowedFiles.contains(uri)) {
            String uriLC = uri.toLowerCase();
            if (uriLC.endsWith(".svg")) {
                response.setContentType("image/svg+xml");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri)), false));
            } else if (uriLC.endsWith(".css")) {
                response.setContentType("text/css");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.styleResourcePath + "/" + uri)), false));
            } else if (uriLC.endsWith(".json")) {
                response.setContentType("application/json");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.miscResourcePath + "/" + uri)), false));
            } else if (uriLC.endsWith(".js")) {
                response.setContentType("text/javascript");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.scriptResourcePath + "/" + uri)), false));
            } else if (uriLC.endsWith(".png")) {
                response.setContentType("image/png");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri)), false));
            } else if (uriLC.endsWith(".ico")) {
                response.setContentType("image/x-icon");
                response.getOutputStream().write(byteUtils.readBytes(new File((SimpleChatApplication.imageResourcePath + "/" + uri)), false));
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return false;
        }

        if (uri.startsWith("/image-file-preview/")) {
            String fileInfoId = uri.substring(uri.indexOf('/', 1) + 1);
            FileInfo info = fileInfoRepository.findById(UUID.fromString(fileInfoId)).get();
            MediaType mediaType;
            byte fileData[];
            if (info.getImgPrevFileDataId() == null) {
                mediaType = byteUtils.getMediaType(info.getName());
                fileData = byteUtils.readBytes(new File(SimpleChatApplication.uploadPath + "/" + info.getFileDataId()), true);
            } else {
                mediaType = byteUtils.getMediaType("a." + SimpleChatApplication.imgThumbnailFormat);
                fileData = byteUtils.readBytes(new File(SimpleChatApplication.uploadPath + "/" + info.getImgPrevFileDataId()), true);
            }
            response.setContentType("" + mediaType);
            response.getOutputStream().write(fileData);
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
            if (uri.equals("/user")) {
                params.put("optionsSvg",
                        "<img src=\"options.svg\" "
                        + "id=\"OptionsSvg\" "
                        + "class=\"Button ButtonTransPrimary\""
                        + "onclick='toggleSidebarDisplay()'/>");
            } else {
                params.put("optionsSvg", "");
            }
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
