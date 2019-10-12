package simplechat.model;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;

@Entity
@NoArgsConstructor
@Getter
@Setter
public class UnreadMessageCounter extends BaseModel {

    @Column(length = 100)
    private String currentSideUsername;

    @Column(length = 100)
    private String otherSideUsername;

    @Column
    private int count;
}
