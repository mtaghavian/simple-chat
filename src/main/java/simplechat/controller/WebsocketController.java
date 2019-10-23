package simplechat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import simplechat.SimpleChatApplication;
import simplechat.config.HttpInterceptor;
import simplechat.model.*;
import simplechat.repository.*;
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

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UnreadMessageCounterRepository unreadMessageCounterRepository;

    @Autowired
    private FileInfoRepository fileInfoRepository;

    @Autowired
    private FileDataRepository fileDataRepository;

    @Autowired
    private HttpInterceptor httpInterceptor;

    @Autowired
    @Value("${loadingMessagesChunksize}")
    public int loadingMessagesChunksize;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<String, Session> sessionMapFromWSS = new HashMap<>();
    private final Map<String, Session> sessionMapFromUN = new HashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession wss) throws IOException {
        try {
            lock.lock();
            Session session = getSession(wss);
            if ((session != null) && (session.getUser() != null)) {
                session.setWebSocketSession(wss);
                sessionMapFromWSS.put(wss.getId(), session);
                sessionMapFromUN.put(session.getUser().getUsername(), session);
                changePage(SimpleChatApplication.broadcastUsername, session, session.getUser());
                session.getWebSocketSession().sendMessage(new TextMessage(
                        createUsersListUIComponent(session.getUser().getUsername(), session.getOtherSideUsername())));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handleMessage(WebSocketSession wss, WebSocketMessage<?> webSocketMessage) throws Exception {
        try {
            lock.lock();
            if (!sessionMapFromWSS.containsKey(wss.getId())) {
                return;
            }
            Session currentSession = sessionMapFromWSS.get(wss.getId());
            User currentUser = currentSession.getUser();
            String payload = ((TextMessage) webSocketMessage).getPayload();
            String cmd = payload.substring(0, payload.indexOf("\n"));
            String body = payload.substring(payload.indexOf("\n") + 1, payload.length());
            if ("msg".equals(cmd)) {
                Message msg = new Message();
                msg.setTextMessage(true);
                msg.setBody(body);
                msg.setDate(System.currentTimeMillis());
                msg.setSenderPresentation(currentUser.getPresentation());
                msg.setSenderUsername(currentUser.getUsername());
                msg.setReceiverUsername(currentSession.getOtherSideUsername());

                routeMessage(currentUser.getUsername(), msg);
            } else if ("change-page".equals(cmd)) {
                changePage(body, currentSession, currentUser);
            } else if ("delete-msg".equals(cmd)) {
                Message msg = messageRepository.findById(UUID.fromString(body)).get();
                if (!msg.isTextMessage()) {
                    FileInfo fileInfo = fileInfoRepository.findById(msg.getFileInfoId()).get();
                    fileDataRepository.deleteById(fileInfo.getFileDataId());
                    if (fileInfo.getImgPrevFileDataId() != null) {
                        fileDataRepository.deleteById(fileInfo.getImgPrevFileDataId());
                    }
                    fileInfoRepository.deleteById(fileInfo.getId());
                }
                messageRepository.deleteById(msg.getId());
                String pack = "delete-msg\n" + body;
                routePacket(currentUser.getUsername(), currentSession.getOtherSideUsername(), pack, pack, currentSession);
            } else if ("top".equals(cmd)) {
                String otherSideUsername = currentSession.getOtherSideUsername();
                sendMessages(currentUser.getUsername(), otherSideUsername, currentSession, Long.parseLong(body), "load");
            } else if ("ping".equals(cmd)) {
                currentSession.getWebSocketSession().sendMessage(new TextMessage("pong\n"));
            } else {
                logger.error("Unsupported command!");
            }
        } finally {
            lock.unlock();
        }
    }

    private void sendMessages(String currentSideUsername, String otherSideUsername, Session currentSession, long date, String cmd) throws IOException {
        List<Message> messages;
        if (SimpleChatApplication.broadcastUsername.equals(otherSideUsername)) {
            messages = messageRepository.fetchMessages(loadingMessagesChunksize, otherSideUsername, date);
        } else {
            messages = messageRepository.fetchMessages(loadingMessagesChunksize, currentSideUsername, otherSideUsername, date);
        }
        Collections.reverse(messages);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.isTextMessage()) {
                sb.append(createTextMessageUIComponent(m, currentSideUsername.equals(m.getSenderUsername())));
            } else {
                sb.append(createFileMessageUIComponent(m, currentSideUsername.equals(m.getSenderUsername())));
            }
        }
        currentSession.getWebSocketSession().sendMessage(new TextMessage(cmd + "\n" + sb.toString()));
        if (messages.size() > 0) {
            currentSession.getWebSocketSession().sendMessage(new TextMessage("checkForLoadingMore\n"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wss, CloseStatus closeStatus) throws Exception {
        try {
            lock.lock();
            if (sessionMapFromWSS.containsKey(wss.getId())) {
                sessionMapFromUN.remove(sessionMapFromWSS.get(wss.getId()).getUser().getUsername());
                sessionMapFromWSS.remove(wss.getId());
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

    public void sendFile(User sender, FileInfo info) throws IOException {
        Message msg = new Message();
        msg.setTextMessage(false);
        msg.setImageFile(info.getImgPrevFileDataId() != null);
        msg.setBody(info.getName() + " (" + byteUtils.humanReadableSize(info.getLength()) + ")");
        msg.setDate(System.currentTimeMillis());
        msg.setSenderPresentation(sender.getPresentation());
        msg.setFileInfoId(info.getId());
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
            sessionMapFromWSS.values().forEach(x -> {
                try {
                    if (x.getUser() != null) {
                        if (excludeUsername != null && x.getOtherSideUsername().equals(excludeUsername)) {
                            x.setOtherSideUsername(SimpleChatApplication.broadcastUsername);
                        }
                        x.getWebSocketSession().sendMessage(new TextMessage(
                                createUsersListUIComponent(x.getUser().getUsername(), x.getOtherSideUsername())));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            if (excludeUsername != null) {
                List<Message> messages = messageRepository.findAllBySenderUsername(excludeUsername);
                messages.addAll(messageRepository.findAllByReceiverUsername(excludeUsername));
                messageRepository.deleteAll(messages);
                List<UnreadMessageCounter> unreadMessageCounters = unreadMessageCounterRepository.findAllByCurrentSideUsername(excludeUsername);
                unreadMessageCounters.addAll(unreadMessageCounterRepository.findAllByOtherSideUsername(excludeUsername));
                unreadMessageCounterRepository.deleteAll(unreadMessageCounters);
            }
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
                    Optional<Session> sessionOptional = sessionRepository.findById(sid);
                    if (sessionOptional.isPresent()) {
                        return sessionOptional.get();
                    } else {
                        Session session = new Session(sid, null, System.currentTimeMillis());
                        httpInterceptor.loginWithCookies(properties, session);
                        if (session.getUser() != null) {
                            sessionRepository.save(session);
                            return session;
                        } else {
                            wss.sendMessage(new TextMessage("redirect\n/"));
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private String createFileMessageUIComponent(Message msg, boolean self) throws IOException {
        String text = "";
        Map<String, String> params = new HashMap<>();
        params.put("id", "" + msg.getId());
        params.put("date", "date=\"" + msg.getDate() + "\"");
        params.put("onclick", " onclick=\'download(\"" + msg.getFileInfoId() + "\")\' ");
        params.put("body", msg.getBody());
        params.put("dateStr", byteUtils.formatTime(msg.getDate()));
        if (msg.isImageFile()) {
            params.put("image", "<img src=\"image-file-preview/" + msg.getFileInfoId() + "\" class=\"ChatImgFileMsgAttachment\"/>");
        } else {
            params.put("image", "<img src=\"attachment.svg\" class=\"ChatFileMsgAttachmentSvg\"/>");
        }
        if (self) {
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            params.put("title", msg.getSenderPresentation());
            text += byteUtils.readPage("/chat-msg-left.html", params);
        }
        return text;
    }

    private String createTextMessageUIComponent(Message msg, boolean self) throws IOException {
        String text = "";
        Map<String, String> params = new HashMap<>();
        params.put("id", "" + msg.getId());
        params.put("date", "date=\"" + msg.getDate() + "\"");
        params.put("image", "");
        params.put("onclick", "");
        params.put("body", msg.getBody());
        params.put("dateStr", byteUtils.formatTime(msg.getDate()));
        if (self) {
            text += byteUtils.readPage("/chat-msg-right.html", params);
        } else {
            params.put("title", msg.getSenderPresentation());
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
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            params.clear();
            if (user.getUsername().equals(activeUsername)) {
                params.put("name", user.getPresentation());
                text += byteUtils.readPage("/sidebar-entry-active.html", params);
            } else {
                params.put("name", user.getPresentation());
                params.put("onclick", " onclick=\'changePage(\"" + user.getUsername() + "\")\' ");
                UnreadMessageCounter unreadMessageCounter = unreadMessageCounterRepository.findByCurrentSideUsernameAndOtherSideUsername(username, user.getUsername());
                Integer count = (unreadMessageCounter == null) ? 0 : unreadMessageCounter.getCount();
                params.put("count", (count == 0) ? "" :
                        "<div class=\"SidebarEntryUnreadCount SimpleText SimpleFont\">" + count + "</div>");
                text += byteUtils.readPage("/sidebar-entry-passive.html", params);
            }
        }
        return text;
    }

    private void changePage(String otherSideUsername, Session session, User user) throws IOException {
        session.setOtherSideUsername(otherSideUsername);
        session.getWebSocketSession().sendMessage(new TextMessage(
                createUsersListUIComponent(session.getUser().getUsername(), otherSideUsername)));
        sendMessages(user.getUsername(), otherSideUsername, session, System.currentTimeMillis(), "page");
        UnreadMessageCounter unreadMessageCounter = unreadMessageCounterRepository.findByCurrentSideUsernameAndOtherSideUsername(user.getUsername(), otherSideUsername);
        if (unreadMessageCounter != null) {
            unreadMessageCounterRepository.delete(unreadMessageCounter);
        }
    }

    private void routeMessage(String senderUsername, Message msg) throws IOException {
        Session currentSession = sessionMapFromUN.get(senderUsername);
        String otherSideUsername = currentSession.getOtherSideUsername();
        msg.setSenderUsername(senderUsername);
        msg.setReceiverUsername(otherSideUsername);
        messageRepository.save(msg);
        String selfPack = "msg\n" + (msg.isTextMessage() ? createTextMessageUIComponent(msg, true) : createFileMessageUIComponent(msg, true));
        String otherPack = "msg\n" + (msg.isTextMessage() ? createTextMessageUIComponent(msg, false) : createFileMessageUIComponent(msg, false));
        routePacket(senderUsername, otherSideUsername, selfPack, otherPack, currentSession);
    }

    private void routePacket(String senderUsername, String otherSideUsername, String selfPack, String otherPack, Session currentSession) throws IOException {
        currentSession.getWebSocketSession().sendMessage(new TextMessage(selfPack));
        if (otherSideUsername.equals(SimpleChatApplication.broadcastUsername)) {
            for (User user : userRepository.findAll()) {
                String username = user.getUsername();
                if (username.equals(senderUsername) || username.equals(SimpleChatApplication.broadcastUsername)) {
                    continue;
                }
                Session userSession = sessionMapFromUN.get(username);
                sendOtherSideMessage(otherPack, otherSideUsername, username, userSession);
            }
        } else {
            if (!otherSideUsername.equals(senderUsername)) {
                Session otherSideSession = sessionMapFromUN.get(otherSideUsername);
                sendOtherSideMessage(otherPack, senderUsername, otherSideUsername, otherSideSession);
            }
        }
    }

    private void sendOtherSideMessage(String msg, String otherSideUsername, String senderUsername, Session session) throws IOException {
        if ((session != null) && session.getOtherSideUsername().equals(otherSideUsername)) {
            session.getWebSocketSession().sendMessage(new TextMessage(msg));
        } else {
            UnreadMessageCounter unreadMessageCounter = unreadMessageCounterRepository.findByCurrentSideUsernameAndOtherSideUsername(senderUsername, otherSideUsername);
            int cnt = (unreadMessageCounter == null) ? 1 : (unreadMessageCounter.getCount() + 1);
            if (unreadMessageCounter == null) {
                unreadMessageCounter = new UnreadMessageCounter();
                unreadMessageCounter.setCurrentSideUsername(senderUsername);
                unreadMessageCounter.setOtherSideUsername(otherSideUsername);
            }
            unreadMessageCounter.setCount(cnt);
            unreadMessageCounterRepository.save(unreadMessageCounter);
            if (session != null) {
                session.getWebSocketSession().sendMessage(new TextMessage(
                        createUsersListUIComponent(session.getUser().getUsername(), session.getOtherSideUsername())));
            }
        }
    }
}
