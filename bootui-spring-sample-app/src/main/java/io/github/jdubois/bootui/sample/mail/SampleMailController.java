package io.github.jdubois.bootui.sample.mail;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers one more sample outgoing email on demand so the BootUI Email panel can be exercised
 * interactively (and by the Playwright e2e suite). The email is captured by BootUI and, because the
 * sample app runs in dev-trap mode, never actually sent.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleMailController {

    private final SampleMailSender mailSender;

    public SampleMailController(SampleMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @GetMapping("/send-email")
    public String sendEmail() {
        return mailSender.sendSampleEmail();
    }
}
