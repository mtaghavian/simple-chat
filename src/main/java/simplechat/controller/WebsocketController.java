package simplechat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static Map<String, Map<String, Integer>> unreadCountMap = new HashMap<>();
    private static ReentrantLock unreadCountMapLock = new ReentrantLock(true);
    private static Map<String, Map<String, List<Message>>> messageMap = new HashMap<>();
    private static ReentrantLock messageMapLock = new ReentrantLock(true);

    private final static Map<String, Session> sessionMap = new HashMap<>();
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void afterConnectionEstablished(WebSocketSession wss) throws IOException {
        Session session = getSession(wss);
        if (session != null) {
            session.setWebSocketSession(wss);
            session.setChateeUsername(SimpleChatApplication.broadcastUsername);
            sessionMap.put(wss.getId(), session);

            session.getWebSocketSession().sendMessage(new TextMessage(
                    createUsersListUIComponent(session.getUser().getUsername(), session.getChateeUsername())));
        }
    }

    @Override
    public void handleMessage(WebSocketSession wss, WebSocketMessage<?> webSocketMessage) throws Exception {
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
            currentSession.setChateeUsername(chatee);
            currentSession.getWebSocketSession().sendMessage(new TextMessage(
                    createUsersListUIComponent(currentSession.getUser().getUsername(), chatee)));
            messageMapLock.lock();
            String messagesStr;
            try {
                updateMessageMap(userRepository.findAll());
                List<Message> messages = messageMap.get(currentUser.getUsername()).get(chatee);
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
            } finally {
                messageMapLock.unlock();
            }
            currentSession.getWebSocketSession().sendMessage(new TextMessage("msg\n" + messagesStr));
            unreadCountMapLock.lock();
            try {
                updateUnreadCountMap(userRepository.findAll());
                unreadCountMap.get(currentUser.getUsername()).put(chatee, 0);
            } finally {
                unreadCountMapLock.unlock();
            }
        } else {
            logger.error("Unsupported command!");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wss, CloseStatus closeStatus) throws Exception {
        if (sessionMap.containsKey(wss.getId())) {
            sessionMap.remove(wss.getId());
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
        msg.setBody(uploadedFile.getName() + " (" + byteUtils.humanReadableSize(uploadedFile.getLength()) + ")");
        msg.setDate(System.currentTimeMillis());
        msg.setSender(sender.getPresentation());
        msg.setSelf(true);
        msg.setId(uploadedFile.getId());
        routeMessage(sender.getUsername(), msg);
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
        if (msg.isSelf()) {
            Map<String, String> params = new HashMap<>();
            params.put("fileIcon", "inline-block");
            params.put("fileLink", " id=\"" + msg.getId() + "\" onclick=\'download(\"" + msg.getId() + "\")\' ");
            params.put("body", msg.getBody() + ")");
            params.put("date", byteUtils.formatTime(msg.getDate()));
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            Map<String, String> params = new HashMap<>();
            params.put("fileIcon", "inline-block");
            params.put("fileLink", " onclick=\'download(\"" + msg.getId() + "\")\' ");
            params.put("body", msg.getBody());
            params.put("date", byteUtils.formatTime(msg.getDate()));
            params.put("title", msg.getSender());
            text += byteUtils.readPage("/chat-msg-left.html", params);
        }
        return text;
    }

    private String createTextMessageUIComponent(Message msg) throws IOException {
        String text = "";
        if (msg.isSelf()) {
            Map<String, String> params = new HashMap<>();
            params.put("fileIcon", "none");
            params.put("fileLink", "");
            params.put("body", msg.getBody());
            params.put("date", byteUtils.formatTime(msg.getDate()));
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            Map<String, String> params = new HashMap<>();
            params.put("fileIcon", "none");
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
        Collections.sort(users);
        int fi = 0;
        for (int i = 0; i < users.size(); i++) {
            if (activeUsername.equals(users.get(i).getUsername())) {
                fi = i;
                break;
            }
        }
        if (fi != 0) {
            User u = users.get(fi);
            users.remove(fi);
            users.add(0, u);
        }
        String text = "users\n";
        Map<String, String> params = new HashMap<>();
        params.put("name", users.get(0).getPresentation());
        text += byteUtils.readPage("/sidebar-entry-active.html", params) + "\n";
        unreadCountMapLock.lock();
        try {
            updateUnreadCountMap(users);
            Map<String, Integer> countMap = unreadCountMap.get(username);
            for (int i = 1; i < users.size(); i++) {
                User user = users.get(i);
                params.put("name", user.getPresentation());
                params.put("onclick", " onclick=\'changePage(\"" + user.getUsername() + "\")\' ");
                Integer count = countMap.get(user.getUsername());
                params.put("count", (count == 0) ? "" :
                        "<div class=\"SidebarEntryUnreadCount SimpleText SimpleFont\">" + count + "</div>");
                text += byteUtils.readPage("/sidebar-entry-passive.html", params) + "\n";
            }
        } finally {
            unreadCountMapLock.unlock();
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
            for (String un : map.keySet()) {
                if (!usernameSet.contains(un)) {
                    map.remove(un);
                }
            }
        }
        for (String un : unreadCountMap.keySet()) {
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
            for (String un : map.keySet()) {
                if (!usernameSet.contains(un)) {
                    map.remove(un);
                }
            }
        }
        for (String un : messageMap.keySet()) {
            if (!usernameSet.contains(un)) {
                messageMap.remove(un);
            }
        }
    }

    public void routeMessage(String senderUsername, Message msg) throws IOException {
        messageMapLock.lock();
        try {
            Session currentSession = getUserSession(senderUsername);
            String chatee = currentSession.getChateeUsername();
            updateMessageMap(userRepository.findAll());
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
                        unreadCountMapLock.lock();
                        try {
                            updateUnreadCountMap(userRepository.findAll());
                            Integer cnt = unreadCountMap.get(username).get(SimpleChatApplication.broadcastUsername) + 1;
                            unreadCountMap.get(username).put(SimpleChatApplication.broadcastUsername, cnt);
                        } finally {
                            unreadCountMapLock.unlock();
                        }
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
                    unreadCountMapLock.lock();
                    try {
                        updateUnreadCountMap(userRepository.findAll());
                        Integer cnt = unreadCountMap.get(chatee).get(senderUsername) + 1;
                        unreadCountMap.get(chatee).put(senderUsername, cnt);
                    } finally {
                        unreadCountMapLock.unlock();
                    }
                    if (chateeSession != null) {
                        chateeSession.getWebSocketSession().sendMessage(new TextMessage(
                                createUsersListUIComponent(chateeSession.getUser().getUsername(), chateeSession.getChateeUsername())));
                    }
                }
            }
        } finally {
            messageMapLock.unlock();
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
