package dev.jasmeet.taskmanager.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "tasks")
public class Task {

    // The request DTOs use these same constants in their @Size annotations,
    // so the validation limit and the column length cannot drift apart.
    public static final int TITLE_MAX = 200;
    public static final int DESCRIPTION_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected Task() {
        // required by JPA
    }

    public Task(String title, String description, LocalDate dueDate, Priority priority, Status status) {
        assign(title, description, dueDate, priority, status);
    }

    /** Full replacement of every client-editable field (PUT semantics). */
    public void update(String title, String description, LocalDate dueDate, Priority priority, Status status) {
        assign(title, description, dueDate, priority, status);
    }

    // Private so the constructor never calls an overridable method
    // (Hibernate may subclass entities for proxies, so update() must stay non-final).
    private void assign(String title, String description, LocalDate dueDate, Priority priority, Status status) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = priority;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDate getDueDate() { return dueDate; }
    public Priority getPriority() { return priority; }
    public Status getStatus() { return status; }
}
