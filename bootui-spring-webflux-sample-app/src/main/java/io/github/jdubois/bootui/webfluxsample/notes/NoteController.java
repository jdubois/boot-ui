package io.github.jdubois.bootui.webfluxsample.notes;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive REST endpoints over the blocking {@link NoteRepository}. Every call wraps the blocking JDBC
 * work in {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} so it never runs on
 * Netty's small event-loop thread pool - the same pattern BootUI's own reactive panels (Flyway, Liquibase,
 * Database Connection Pools, GitHub, OSV) use internally for their own blocking calls.
 */
@RestController
public class NoteController {

    private final NoteRepository noteRepository;

    public NoteController(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    @GetMapping("/api/notes")
    public Flux<Note> notes() {
        return Mono.fromCallable(noteRepository::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @GetMapping("/api/notes/{id}")
    public Mono<Note> note(@PathVariable long id) {
        return Mono.fromCallable(() -> noteRepository.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found")));
    }
}
