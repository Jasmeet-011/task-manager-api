package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    // Internal sort key: Priority.rank() (LOW=1, MEDIUM=2, HIGH=3). The priority column holds text, which
    // sorts alphabetically (HIGH, LOW, MEDIUM). Only assign() writes this field, in the same step as
    // priority, so the two cannot drift apart. No getter, and it is never part of TaskResponse.
    @Column(name = "priority_rank", nullable = false)
    private int priorityRank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    // ADR-002: many tasks to one optional project, owned by this side only.
    // LAZY so loading a task does not load its project. No cascade: tasks never create,
    // update or delete projects. Only moveTo and removeFromProject change this field.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

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

    /** Puts the task in the given project. Moves it if it was in another one. */
    public void moveTo(Project project) {
        this.project = project;
    }

    public void removeFromProject() {
        this.project = null;
    }

    // Private so the constructor never calls an overridable method
    // (Hibernate may subclass entities for proxies, so update() must stay non-final).
    private void assign(String title, String description, LocalDate dueDate, Priority priority, Status status) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = priority;
        this.priorityRank = priority.rank();
        this.status = status;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDate getDueDate() { return dueDate; }
    public Priority getPriority() { return priority; }
    public Status getStatus() { return status; }
    public Project getProject() { return project; }
}
