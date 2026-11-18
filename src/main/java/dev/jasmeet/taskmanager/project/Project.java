package dev.jasmeet.taskmanager.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "projects",
        uniqueConstraints = @UniqueConstraint(name = Project.NAME_UNIQUE_CONSTRAINT, columnNames = "name"))
public class Project {

    // The request DTO uses these same constants in its @Size annotations,
    // so the validation limit and the column length cannot drift apart.
    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;

    // The service recognises a duplicate name by this constraint name, so a future
    // unique constraint on this table would not be mistaken for a duplicate name.
    public static final String NAME_UNIQUE_CONSTRAINT = "uk_projects_name";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    protected Project() {
        // required by JPA
    }

    public Project(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
