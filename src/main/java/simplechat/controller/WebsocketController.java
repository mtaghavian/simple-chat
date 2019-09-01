package simplechat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.util.UriUtils;
import simplechat.model.Session;
import simplechat.model.User;
import simplechat.repository.SessionRepository;
import simplechat.util.ByteUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;

@Component
public class WebsocketController implements WebSocketHandler {

    @Autowired
    private ByteUtils byteUtils;

    @Autowired
    private SessionRepository sessionRepository;

    private final static Map<String, WebSocketSession> wsSessionIdMap = new HashMap<>();
    private final static Map<String, Session> sessionMap = new HashMap<>();
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void afterConnectionEstablished(WebSocketSession wss) throws Exception {
        Session session = getSession(wss);
        if (session != null) {
            sessionMap.put(wss.getId(), session);
            wsSessionIdMap.put(wss.getId(), wss);
        }
    }

    @Override
    public void handleMessage(WebSocketSession wss, WebSocketMessage<?> webSocketMessage) throws Exception {
        if (!wsSessionIdMap.containsKey(wss.getId())) {
            return;
        }
        User sender = sessionMap.get(wss.getId()).getUser();
        String message = ((TextMessage) webSocketMessage).getPayload();
        for (String sid : wsSessionIdMap.keySet()) {
            User receiver = sessionMap.get(sid).getUser();
            String text;
            if (sender.getId().equals(receiver.getId())) {
                Map<String, String> params = new HashMap<>();
                params.put("fileIcon", "none");
                params.put("fileLink", "");
                params.put("body", message);
                text = byteUtils.readPage("/chat-msg-right.html", params);
            } else {
                Map<String, String> params = new HashMap<>();
                params.put("fileIcon", "none");
                params.put("fileLink", "");
                params.put("body", message);
                params.put("title", sender.getFirstname() + " " + sender.getLastname());
                text = byteUtils.readPage("/chat-msg-left.html", params);
            }
            wsSessionIdMap.get(sid).sendMessage(new TextMessage(text));
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

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable throwable) throws Exception {
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wss, CloseStatus closeStatus) throws Exception {
        if (wsSessionIdMap.containsKey(wss.getId())) {
            wsSessionIdMap.remove(wss.getId());
            sessionMap.remove(wss.getId());
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    public void sendFile(User sender, File file) throws IOException {
        for (String sid : wsSessionIdMap.keySet()) {
            User receiver = sessionMap.get(sid).getUser();
            String text;
            String id = UriUtils.encode(file.getName(), "UTF-8");
            if (sender.getId().equals(receiver.getId())) {
                Map<String, String> params = new HashMap<>();
                params.put("fileIcon", "inline-block");
                params.put("fileLink", " id=\"" + id + "\" onclick=\'download(\"" + id + "\")\' ");
                params.put("body", file.getName() + " (" + byteUtils.humanReadableSize(file.length()) + ")");
                text = byteUtils.readPage("/chat-msg-right.html", params);
            } else {
                Map<String, String> params = new HashMap<>();
                params.put("fileIcon", "inline-block");
                params.put("fileLink", " onclick=\'download(\"" + id + "\")\' ");
                params.put("body", file.getName() + " (" + byteUtils.humanReadableSize(file.length()) + ")");
                params.put("title", sender.getFirstname() + " " + sender.getLastname());
                text = byteUtils.readPage("/chat-msg-left.html", params);
            }
            wsSessionIdMap.get(sid).sendMessage(new TextMessage(text));
        }
    }
}
