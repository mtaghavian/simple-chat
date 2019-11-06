
package simplechat.model;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Lob;


@Entity
@NoArgsConstructor
@Getter
@Setter
public class MyData extends BaseModel {

    @Lob
    @Column
    private byte[] data;
}