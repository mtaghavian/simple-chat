package simplechat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import simplechat.model.Message;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    public List<Message> findAllByReceiverUsername(String receiver);

    public List<Message> findAllBySenderUsername(String receiver);

    @Query(value = "select * from message m where(m.date < :date) and " +
            "(m.receiver_username=:receiver) " +
            "order by m.date desc limit :limit", nativeQuery = true)
    public List<Message> fetchMessages(@Param("limit") int limit, @Param("receiver") String receiver, @Param("date") long date);

    @Query(value = "select * from message m where (m.date < :date) and " +
            "((m.sender_username=:sender and m.receiver_username=:receiver) or " +
            "(m.sender_username=:receiver and m.receiver_username=:sender)) " +
            "order by m.date desc limit :limit", nativeQuery = true)
    public List<Message> fetchMessages(@Param("limit") int limit, @Param("sender") String sender, @Param("receiver") String receiver, @Param("date") long date);
}
