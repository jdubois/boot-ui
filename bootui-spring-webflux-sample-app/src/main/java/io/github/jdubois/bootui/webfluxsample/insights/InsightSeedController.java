package io.github.jdubois.bootui.webfluxsample.insights;

import io.github.jdubois.bootui.webfluxsample.notes.Note;
import io.github.jdubois.bootui.webfluxsample.notes.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runtime Insights seeds on Spring WebFlux ({@code docs/PLAN-v2.md} M3-6): blocking JDBC run on the Netty event loop,
 * and a per-note loop of statements, each beside the counterexample it must not report: {@code /api/notes} moves the
 * same JDBC to {@code boundedElastic}, and {@code /api/sample/rest-client} completes an asynchronous client on the event
 * loop, which is normal. Never copy these routes into an application.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightSeedController {

    /** The notes the loop reads one at a time, more than the five repeats {@code repeated-selects} needs. */
    private static final int NOTES = 6;

    private final NoteRepository notes;
    private final WebClient webClient;
    private final WebServerApplicationContext context;

    public InsightSeedController(
            NoteRepository notes, WebClient.Builder webClients, WebServerApplicationContext context) {
        this.notes = notes;
        this.webClient = webClients.build();
        this.context = context;
    }

    /**
     * {@code event-loop-blocking}: an asynchronous greeting call, then the notes read with blocking JDBC in its {@code
     * map}, which runs on the event-loop thread the response completed on.
     */
    @GetMapping("/notes/on-event-loop")
    public Mono<List<Note>> notesOnTheEventLoop() {
        return webClient
                .get()
                .uri("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/greetings/{name}", "notes")
                .retrieve()
                .bodyToMono(String.class)
                .map(greeting -> notes.findAll());
    }

    /** {@code repeated-selects}: every note, then each note again by id, off the event loop. */
    @GetMapping("/notes/one-by-one")
    public Mono<List<Note>> notesOneByOne() {
        return Mono.fromCallable(() -> {
                    notes.findAll();
                    List<Note> found = new ArrayList<>();
                    for (long id = 1; id <= NOTES; id++) {
                        notes.findById(id).ifPresent(found::add);
                    }
                    return found;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** The counterexample of the loop: one statement for every note. */
    @GetMapping("/notes/at-once")
    public Mono<List<Note>> notesAtOnce() {
        return Mono.fromCallable(notes::findAll).subscribeOn(Schedulers.boundedElastic());
    }
}
