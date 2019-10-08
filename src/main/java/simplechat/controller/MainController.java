package simplechat.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import simplechat.SimpleChatApplication;
import simplechat.model.Session;
import simplechat.model.UploadedFile;
import simplechat.model.User;
import simplechat.repository.SessionRepository;
import simplechat.repository.UploadedFileRepository;
import simplechat.repository.UserRepository;
import simplechat.util.ByteUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class MainController {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ByteUtils byteUtils;

    @Autowired
    private WebsocketController websocketController;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    private User getUser(HttpSession session) {
        return sessionRepository.findById(session.getId()).get().getUser();
    }

    @PostMapping("/change-password")
    public String changePassword(HttpServletRequest request, HttpServletResponse response, @RequestBody Map<String, String> params) {
        String currentPassword = params.get("currentPassword");
        String newPassword = params.get("newPassword");
        Boolean updateCookies = Boolean.parseBoolean(params.get("updateCookies"));
        HttpSession httpSession = request.getSession();
        User user = getUser(httpSession);
        if (user.getPassword().equals(byteUtils.hash(currentPassword))) {
            String problem = User.validatePassword(newPassword);
            if (problem == null) {
                user.setPassword(byteUtils.hash(newPassword));
                userRepository.save(user);
                if (updateCookies) {
                    Cookie usernameCookie = new Cookie("username", user.getUsername());
                    usernameCookie.setMaxAge(60 * 60 * 24 * 30 * 12);
                    response.addCookie(usernameCookie);
                    Cookie passwordCookie = new Cookie("password", user.getPassword());
                    passwordCookie.setMaxAge(60 * 60 * 24 * 30 * 12);
                    response.addCookie(passwordCookie);
                }
                return "Yes";
            } else {
                return "No\n" + problem;
            }
        } else {
            return "No\nIncorrect current password";
        }
    }

    @PostMapping("/login-helper")
    public String loginHelper(HttpServletRequest request, HttpServletResponse response, @RequestBody User user) throws IOException {
        HttpSession httpSession = request.getSession();
        User dbUser = userRepository.findByUsername(user.getUsername());
        if ((dbUser != null) && dbUser.getPassword().equals(byteUtils.hash(user.getPassword()))) {
            if (!sessionRepository.findByUsername(dbUser.getUsername()).isEmpty()) {
                return "No\nCurrently, there is someone online with this username";
            } else {
                Session session = sessionRepository.findById(httpSession.getId()).get();
                session.setUser(dbUser);
                if (user.getRememberMe()) {
                    Cookie usernameCookie = new Cookie("username", user.getUsername());
                    usernameCookie.setMaxAge(60 * 60 * 24 * 30 * 12);
                    response.addCookie(usernameCookie);
                    Cookie passwordCookie = new Cookie("password", byteUtils.hash(user.getPassword()));
                    passwordCookie.setMaxAge(60 * 60 * 24 * 30 * 12);
                    response.addCookie(passwordCookie);
                }
                String redirectedUri = session.getRedirectedUri();
                redirectedUri = (redirectedUri != null) ? redirectedUri : "/user";
                return "Yes\n" + redirectedUri;
            }
        } else {
            return "No\nIncorrect username and/or password";
        }
    }

    @PostMapping("/signup-helper")
    public String signupHelper(HttpServletRequest request, HttpServletResponse response, @RequestBody User user) throws IOException {
        HttpSession httpSession = request.getSession();
        User dbUser = userRepository.findByUsername(user.getUsername());
        if (dbUser == null) {
            String problem = User.validateAll(user);
            if (problem == null) {
                dbUser = new User();
                dbUser.setFirstname(user.getFirstname());
                dbUser.setLastname(user.getLastname());
                dbUser.setUsername(user.getUsername());
                dbUser.setPassword(byteUtils.hash(user.getPassword()));
                userRepository.saveAndFlush(dbUser);
                String result = loginHelper(request, response, user);
                websocketController.updateAllUserLists(null);
                return result;
            } else {
                return "No\n" + problem;
            }
        } else {
            return "No\nDuplicate username";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession httpSession = request.getSession();
        try {
            Cookie usernameCookie = new Cookie("username", "");
            usernameCookie.setMaxAge(0);
            response.addCookie(usernameCookie);
            Cookie passwordCookie = new Cookie("password", "");
            passwordCookie.setMaxAge(0);
            response.addCookie(passwordCookie);
            Session session = sessionRepository.findById(httpSession.getId()).get();
            session.logout();
            response.sendRedirect("/home");
            return "Redirecting";
        } catch (IOException e) {
            e.printStackTrace();
            String exStr = byteUtils.serializeException(e);
            return "<code>" + exStr + "</code>";
        }
    }

    @GetMapping("/deleteAccount")
    public String deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        HttpSession httpSession = request.getSession();
        Session session = sessionRepository.findById(httpSession.getId()).get();
        User user = session.getUser();
        String logout = logout(request, response);
        if ("Redirecting".equals(logout) && !SimpleChatApplication.adminUsername.equals(user.getUsername())) {
            sessionRepository.findByUsername(httpSession.getId())
                    .forEach(x -> x.setUser(null));
            userRepository.deleteById(user.getId());
        }
        websocketController.updateAllUserLists(user.getUsername());
        return logout;
    }

    @GetMapping(value = "/download")
    public ResponseEntity<InputStreamResource> download2(@RequestParam String fileId, HttpServletResponse response) {
        try {
            Optional<UploadedFile> uploadedFile = uploadedFileRepository.findById(UUID.fromString(fileId));
            if (!uploadedFile.isPresent()) {
                throw new RuntimeException("FileNotFound: " + fileId);
            }
            MediaType mediaType = byteUtils.getMediaType(uploadedFile.get().getName());
            File file = new File(SimpleChatApplication.uploadDir + "/" + fileId);
            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
            ResponseEntity<InputStreamResource> body = ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; " +
                            "filename=\"" + uploadedFile.get().getName() + "\"; " +
                            "filename*=UTF-8''" + URLEncoder.encode(uploadedFile.get().getName(), "UTF-8").replace("+", "%20"))
                    .contentLength(file.length())
                    .body(resource);
            return body;
        } catch (IOException ioex) {
            throw new RuntimeException("IOException while reading file: " + fileId);
        }
    }

    @PostMapping(value = "/uploadFile")
    @ResponseBody
    public String uploadFile(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        HttpSession httpSession = request.getSession();
        new File(SimpleChatApplication.uploadDir).mkdir();
        Map<String, String> params = new HashMap<>();
        params.put("pageTitle", "title");
        boolean success = true;
        try {
            UploadedFile uploadedFile = new UploadedFile();
            uploadedFile.setName(file.getOriginalFilename());
            uploadedFileRepository.save(uploadedFile);
            File transferFile = new File(SimpleChatApplication.uploadDir + "/" + uploadedFile.getId());
            FileOutputStream os = new FileOutputStream(transferFile);
            byteUtils.copy(file.getInputStream(), os, false, true);
            uploadedFile.setLength(transferFile.length());
            uploadedFileRepository.save(uploadedFile);
            websocketController.sendFile(getUser(httpSession), uploadedFile);
        } catch (Exception e) {
            e.printStackTrace();
            success = false;
        }
        params.put("body", "<div style=\"line-height: 25px;background-color: #eeeeee;\"> &nbsp;"
                + (success ? "✓" : "✗")
//                + " (" + LocalTime.now() + ")"
                + "</div>");
        try {
            return byteUtils.readPage("/base.html", params);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

}
