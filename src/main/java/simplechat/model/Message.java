package simplechat.model;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
public class Message {

    private boolean textMessage, self;

    private String sender, body;

    private long date;

    private UUID id;

    public Message clone() {
        Message m = new Message();
        m.setTextMessage(textMessage);
        m.setSelf(self);
        m.setSender(sender);
        m.setDate(date);
        m.setBody(body);
        m.setId(id);
        return m;
    }

}
