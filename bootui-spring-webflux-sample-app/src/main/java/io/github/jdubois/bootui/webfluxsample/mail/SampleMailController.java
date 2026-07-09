package io.github.jdubois.bootui.webfluxsample.mail;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive counterpart of the servlet sample app's {@code SampleMailController}. Triggers one more
 * sample outgoing email on demand so the BootUI Email panel can be exercised interactively. The email is
 * captured by BootUI and, because the sample app runs in dev-trap mode, never actually sent.
 *
 * <p>{@code JavaMailSender.send(...)} is a blocking call, so it runs on {@code Schedulers.boundedElastic()},
 * the same off-event-loop pattern {@code SampleActionsController} uses for its own blocking demo actions.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleMailController {

    private final WebfluxSampleMailSender mailSender;

    public SampleMailController(WebfluxSampleMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @GetMapping("/send-email")
    public Mono<String> sendEmail() {
        return Mono.fromCallable(mailSender::sendSampleEmail).subscribeOn(Schedulers.boundedElastic());
    }
}
