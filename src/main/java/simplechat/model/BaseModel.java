package simplechat.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@MappedSuperclass
public class BaseModel {

    @Id
    @GeneratedValue
    private UUID id;

    @Column
    private Date createdDate;

    @Column
    private Date modifiedDate;

    @PrePersist
    public void onPrePersist() {
        createdDate = new Date();
        modifiedDate = createdDate;
    }

    @PreUpdate
    public void onPreUpdate() {
        modifiedDate = new Date();
    }

}
