package simplechat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import simplechat.SimpleChatApplication;
import simplechat.model.Message;
import simplechat.model.Session;
import simplechat.model.UploadedFile;
import simplechat.model.User;
import simplechat.repository.SessionRepository;
import simplechat.repository.UserRepository;
import simplechat.util.ByteUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
public class WebsocketController implements WebSocketHandler {

    @Autowired
    private ByteUtils byteUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private static ReentrantLock lock = new ReentrantLock(true);
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private static Map<String, Map<String, Integer>> unreadCountMap = new HashMap<>();
    private static Map<String, Map<String, List<Message>>> messageMap = new HashMap<>();
    private final static Map<String, Session> sessionMap = new HashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession wss) throws IOException {
        try {
            lock.lock();
            Session session = getSession(wss);
            if ((session != null) && (session.getUser() != null)) {
                session.setWebSocketSession(wss);
                sessionMap.put(wss.getId(), session);
                changePage(SimpleChatApplication.broadcastUsername, session, session.getUser());
                session.getWebSocketSession().sendMessage(new TextMessage(
                        createUsersListUIComponent(session.getUser().getUsername(), session.getChateeUsername())));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handleMessage(WebSocketSession wss, WebSocketMessage<?> webSocketMessage) throws Exception {
        try {
            lock.lock();
            if (!sessionMap.containsKey(wss.getId())) {
                return;
            }
            Session currentSession = sessionMap.get(wss.getId());
            User currentUser = currentSession.getUser();
            String payload = ((TextMessage) webSocketMessage).getPayload();
            String cmd = payload.substring(0, payload.indexOf("\n"));
            String body = payload.substring(payload.indexOf("\n") + 1, payload.length());
            if ("msg".equals(cmd)) {
                Message msg = new Message();
                msg.setTextMessage(true);
                msg.setBody(body);
                msg.setDate(System.currentTimeMillis());
                msg.setSender(currentUser.getPresentation());
                msg.setSelf(true);

                routeMessage(currentUser.getUsername(), msg);
            } else if ("change-page".equals(cmd)) {
                String chatee = body;
                changePage(chatee, currentSession, currentUser);
            } else {
                logger.error("Unsupported command!");
            }
        } finally {
            lock.unlock();
        }

    }

    @Override
    public void afterConnectionClosed(WebSocketSession wss, CloseStatus closeStatus) throws Exception {
        try {
            lock.lock();
            if (sessionMap.containsKey(wss.getId())) {
                sessionMap.get(wss.getId()).logout();
                sessionMap.remove(wss.getId());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable throwable) throws Exception {
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    public void sendFile(User sender, UploadedFile uploadedFile) throws IOException {
        Message msg = new Message();
        msg.setTextMessage(false);
        MediaType mediaType = byteUtils.getMediaType(uploadedFile.getName());
        msg.setImageFile(mediaType != null && mediaType.toString().contains("image"));
        msg.setBody(uploadedFile.getName() + " (" + byteUtils.humanReadableSize(uploadedFile.getLength()) + ")");
        msg.setDate(System.currentTimeMillis());
        msg.setSender(sender.getPresentation());
        msg.setSelf(true);
        msg.setId(uploadedFile.getId());
        if (msg.isImageFile()) {
            byteUtils.makeThumbnailImage(500,
                    new File(SimpleChatApplication.uploadDir + "/" + uploadedFile.getId()),
                    new File(SimpleChatApplication.uploadDir + "/" + uploadedFile.getId() + ".thumbnail.jpg"));
        }
        try {
            lock.lock();
            routeMessage(sender.getUsername(), msg);
        } finally {
            lock.unlock();
        }
    }

    public void updateAllUserLists(String excludeUsername) {
        try {
            lock.lock();
            sessionMap.values().forEach(x -> {
                try {
                    if (x.getUser() != null) {
                        if (excludeUsername != null && x.getChateeUsername().equals(excludeUsername)) {
                            x.setChateeUsername(SimpleChatApplication.broadcastUsername);
                        }
                        x.getWebSocketSession().sendMessage(new TextMessage(
                                createUsersListUIComponent(x.getUser().getUsername(), x.getChateeUsername())));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } finally {
            lock.unlock();
        }
    }

    private Session getSession(WebSocketSession wss) {
        List<String> cookies = wss.getHandshakeHeaders().get("cookie");
        if ((cookies != null) && !cookies.isEmpty()) {
            String foundCookie = null;
            for (String c : cookies) {
                if (c.toLowerCase().contains("jsessionid=")) {
                    foundCookie = c;
                    break;
                }
            }
            if (foundCookie != null) {
                try {
                    Properties properties = new Properties();
                    properties.load(new ByteArrayInputStream(foundCookie.replaceAll(";", "\n").getBytes("UTF-8")));
                    String sid = properties.getProperty("JSESSIONID");
                    Optional<Session> session = sessionRepository.findById(sid);
                    return session.orElse(null);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
        return null;
    }

    private String createFileMessageUIComponent(Message msg) throws IOException {
        String text = "";
        Map<String, String> params = new HashMap<>();
        if (msg.isImageFile()) {
            params.put("image", "<img src=\"image-file-preview/" + msg.getId() + "\" class=\"ChatImgFileMsgAttachment\"/>");
        } else {
            params.put("image", "<img src=\"attachment.svg\" class=\"ChatFileMsgAttachmentSvg\"/>");
        }
        params.put("fileLink", " onclick=\'download(\"" + msg.getId() + "\")\' ");
        params.put("body", msg.getBody());
        params.put("date", byteUtils.formatTime(msg.getDate()));
        if (msg.isSelf()) {
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            params.put("title", msg.getSender());
            text += byteUtils.readPage("/chat-msg-left.html", params);
        }
        return text;
    }

    private String createTextMessageUIComponent(Message msg) throws IOException {
        String text = "";
        if (msg.isSelf()) {
            Map<String, String> params = new HashMap<>();
            params.put("image", "");
            params.put("fileLink", "");
            params.put("body", msg.getBody());
            params.put("date", byteUtils.formatTime(msg.getDate()));
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            Map<String, String> params = new HashMap<>();
            params.put("image", "");
            params.put("fileLink", "");
            params.put("body", msg.getBody());
            params.put("date", byteUtils.formatTime(msg.getDate()));
            params.put("title", msg.getSender());
            text += byteUtils.readPage("/chat-msg-left.html", params);
        }
        return text;
    }

    private String createUsersListUIComponent(String username, String activeUsername) throws IOException {
        List<User> users = userRepository.findAll();
        User broadcastUser = users.stream().filter(x -> x.getUsername().equals(SimpleChatApplication.broadcastUsername))
                .collect(Collectors.toList()).get(0);
        users.remove(broadcastUser);
        Collections.sort(users);
        users.add(0, broadcastUser);
        String text = "users\n";
        Map<String, String> params = new HashMap<>();
        updateUnreadCountMap(users);
        Map<String, Integer> countMap = unreadCountMap.get(username);
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            params.clear();
            if (user.getUsername().equals(activeUsername)) {
                params.put("name", user.getPresentation());
                text += byteUtils.readPage("/sidebar-entry-active.html", params);
            } else {
                params.put("name", user.getPresentation());
                params.put("onclick", " onclick=\'changePage(\"" + user.getUsername() + "\")\' ");
                Integer count = countMap.get(user.getUsername());
                params.put("count", (count == 0) ? "" :
                        "<div class=\"SidebarEntryUnreadCount SimpleText SimpleFont\">" + count + "</div>");
                text += byteUtils.readPage("/sidebar-entry-passive.html", params);
            }
        }
        return text;
    }

    private void updateUnreadCountMap(List<User> users) {
        Set<String> usernameSet = users.stream().map(User::getUsername).collect(Collectors.toSet());
        for (int i = 0; i < users.size(); i++) {
            User from = users.get(i);
            Map<String, Integer> map = unreadCountMap.computeIfAbsent(from.getUsername(), k -> new HashMap<>());
            for (User to : users) {
                map.putIfAbsent(to.getUsername(), 0);
            }
            for (Object un : map.keySet().toArray()) {
                if (!usernameSet.contains(un)) {
                    map.remove(un);
                }
            }
        }
        for (Object un : unreadCountMap.keySet().toArray()) {
            if (!usernameSet.contains(un)) {
                unreadCountMap.remove(un);
            }
        }
    }

    private void updateMessageMap(List<User> users) {
        Set<String> usernameSet = users.stream().map(User::getUsername).collect(Collectors.toSet());
        for (int i = 0; i < users.size(); i++) {
            User from = users.get(i);
            Map<String, List<Message>> map = messageMap.computeIfAbsent(from.getUsername(), k -> new HashMap<>());
            for (User to : users) {
                map.computeIfAbsent(to.getUsername(), k -> new ArrayList<>());
            }
            for (Object un : map.keySet().toArray()) {
                if (!usernameSet.contains(un)) {
                    map.remove(un);
                }
            }
        }
        for (Object un : messageMap.keySet().toArray()) {
            if (!usernameSet.contains(un)) {
                messageMap.remove(un);
            }
        }
    }

    private void changePage(String chatee, Session session, User user) throws IOException {
        updateMessageMap(userRepository.findAll());
        session.setChateeUsername(chatee);
        session.getWebSocketSession().sendMessage(new TextMessage(
                createUsersListUIComponent(session.getUser().getUsername(), chatee)));
        String messagesStr;
        List<Message> messages = messageMap.get(user.getUsername()).get(chatee);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.isTextMessage()) {
                sb.append(createTextMessageUIComponent(m));
            } else {
                sb.append(createFileMessageUIComponent(m));
            }
        }
        messagesStr = sb.toString();
        session.getWebSocketSession().sendMessage(new TextMessage("page\n" + messagesStr));

        updateUnreadCountMap(userRepository.findAll());
        unreadCountMap.get(user.getUsername()).put(chatee, 0);
    }

    private void routeMessage(String senderUsername, Message msg) throws IOException {
        updateMessageMap(userRepository.findAll());
        Session currentSession = getUserSession(senderUsername);
        String chatee = currentSession.getChateeUsername();
        messageMap.get(senderUsername).get(chatee).add(msg);
        currentSession.getWebSocketSession().sendMessage(new TextMessage(
                "msg\n" + (msg.isTextMessage() ? createTextMessageUIComponent(msg) : createFileMessageUIComponent(msg))
        ));
        Message cloneMsg = msg.clone();
        cloneMsg.setSelf(false);
        if (chatee.equals(SimpleChatApplication.broadcastUsername)) {
            for (String username : messageMap.keySet()) {
                if (username.equals(senderUsername)) {
                    continue;
                }
                messageMap.get(username).get(SimpleChatApplication.broadcastUsername).add(cloneMsg);

                Session userSession = getUserSession(username);
                if ((userSession != null) && userSession.getChateeUsername().equals(SimpleChatApplication.broadcastUsername)) {
                    userSession.getWebSocketSession().sendMessage(new TextMessage(
                            "msg\n" + (cloneMsg.isTextMessage() ? createTextMessageUIComponent(cloneMsg)
                                    : createFileMessageUIComponent(cloneMsg))
                    ));
                } else {
                    updateUnreadCountMap(userRepository.findAll());
                    Integer cnt = unreadCountMap.get(username).get(SimpleChatApplication.broadcastUsername) + 1;
                    unreadCountMap.get(username).put(SimpleChatApplication.broadcastUsername, cnt);
                    if (userSession != null) {
                        userSession.getWebSocketSession().sendMessage(new TextMessage(
                                createUsersListUIComponent(userSession.getUser().getUsername(), userSession.getChateeUsername())));
                    }
                }
            }
        } else {
            messageMap.get(chatee).get(senderUsername).add(cloneMsg);
            Session chateeSession = getUserSession(chatee);
            if ((chateeSession != null) && chateeSession.getChateeUsername().equals(senderUsername)) {
                chateeSession.getWebSocketSession().sendMessage(new TextMessage(
                        "msg\n" + (cloneMsg.isTextMessage() ? createTextMessageUIComponent(cloneMsg)
                                : createFileMessageUIComponent(cloneMsg))
                ));
            } else {
                updateUnreadCountMap(userRepository.findAll());
                Integer cnt = unreadCountMap.get(chatee).get(senderUsername) + 1;
                unreadCountMap.get(chatee).put(senderUsername, cnt);
                if (chateeSession != null) {
                    chateeSession.getWebSocketSession().sendMessage(new TextMessage(
                            createUsersListUIComponent(chateeSession.getUser().getUsername(), chateeSession.getChateeUsername())));
                }
            }
        }
    }

    private Session getUserSession(String username) {
        for (String sid : sessionMap.keySet()) {
            if (sessionMap.get(sid).getUser().getUsername().equals(username)) {
                return sessionMap.get(sid);
            }
        }
        return null;
    }

}
