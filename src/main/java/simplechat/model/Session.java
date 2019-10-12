package simplechat.model;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.socket.WebSocketSession;

@NoArgsConstructor
@Getter
@Setter
public class Session {

    private String id;

    private User user;

    private Long lastModified;

    private String redirectedUri;

    private WebSocketSession webSocketSession;

    private String otherSideUsername;

    public Session(String id, User user, Long lastModified) {
        this.id = id;
        this.user = user;
        this.lastModified = lastModified;
    }

    public void logout() {
        setUser(null);
    }
}
