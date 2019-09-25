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
public class UploadedFile extends BaseModel {

    @Column(length = 200)
    private String name;

    @Column
    private Long length;
}
