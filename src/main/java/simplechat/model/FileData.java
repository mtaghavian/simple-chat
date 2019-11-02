package simplechat.model;

import java.sql.Blob;
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
public class FileData extends BaseModel {

    @Lob
    @Column
    private Blob data;
}
