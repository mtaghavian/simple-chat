package simplechat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import simplechat.model.UnreadMessageCounter;

import java.util.List;
import java.util.UUID;

@Repository
public interface UnreadMessageCounterRepository extends JpaRepository<UnreadMessageCounter, UUID> {

    public UnreadMessageCounter findByCurrentSideUsernameAndOtherSideUsername(String cs, String os);

    public List<UnreadMessageCounter> findAllByCurrentSideUsername(String s);

    public List<UnreadMessageCounter> findAllByOtherSideUsername(String s);
}
