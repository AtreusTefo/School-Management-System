package com.example.tracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DELIVERS A RESET LINK - by email where a mail server is configured, and to
 * the application log where one is not.
 *
 * WHY THE LOG FALLBACK IS NOT A STUB
 * ----------------------------------
 * The obvious alternative is to require SMTP and let the feature fail without
 * it. That would make the whole reset flow untestable and undevelopable on any
 * machine without a mail server - which is every machine this project has been
 * developed on - and the usual result is a fake sender that silently does
 * nothing, so nobody notices when the real one breaks.
 *
 * This sends for real when {@code spring.mail.host} is set, and otherwise
 * writes the link at WARN with an explicit note that no mail server is
 * configured. The path through the application is IDENTICAL either way: the
 * same token is generated, stored and expired. Only the last step differs.
 *
 * THE LINK IS LOGGED, AND THAT IS A DELIBERATE DEVELOPMENT-ONLY TRADE.
 * Anything in the log is readable by anyone who can read logs, so a reset link
 * there is a live credential in a place credentials should never be. It is
 * acceptable precisely because it only happens when NO mail server is
 * configured, which is not a state a real deployment is in - and the WARN says
 * so on every single send rather than once at startup, so it cannot be running
 * that way in production without somebody seeing it.
 */
@Component
public class ResetLinkMailer {

    private static final Logger log = LoggerFactory.getLogger(ResetLinkMailer.class);

    /**
     * Optional on purpose. spring-boot-starter-mail always contributes a
     * JavaMailSender bean, but it is only usable once a host is configured -
     * so the host, not the bean, is what decides which path is taken.
     */
    private final Optional<JavaMailSender> mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    /** Where the reset link points. The frontend, not the API. */
    @Value("${app.reset-link-base:http://localhost:4200/reset-password}")
    private String resetLinkBase;

    @Value("${spring.mail.from:no-reply@assignment-tracker.local}")
    private String fromAddress;

    public ResetLinkMailer(Optional<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    /** True when a real mail server is configured. */
    public boolean canSend() {
        return mailSender.isPresent() && mailHost != null && !mailHost.isBlank();
    }

    /**
     * Deliver the link, or log it.
     *
     * NEVER THROWS. A mail server that is down must not turn into a 500 on the
     * forgot-password endpoint, because that endpoint deliberately answers the
     * same way whether or not the account exists - and an error that appeared
     * only for real accounts would hand back exactly the account-enumeration
     * this whole flow is built to avoid.
     */
    public void send(String toAddress, String username, String rawToken) {
        String link = resetLinkBase + "?token=" + rawToken;

        if (!canSend() || toAddress == null || toAddress.isBlank()) {
            log.warn("""
                    NO MAIL SERVER CONFIGURED (spring.mail.host is not set) - password reset \
                    link for '{}' is being written here instead of emailed. This is a \
                    development convenience and must not be how a real deployment runs: \
                    {}""", username, link);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toAddress);
            message.setSubject("Reset your Assignment Tracker password");
            message.setText("""
                    Somebody asked to reset the password for your Assignment Tracker \
                    account (%s).

                    To choose a new one, open this link:
                    %s

                    The link can be used once and expires shortly. If you did not ask for \
                    this, you can ignore this message - your password has not changed.
                    """.formatted(username, link));
            mailSender.get().send(message);
            log.info("Password reset link emailed for '{}'.", username);
        } catch (RuntimeException mailFailed) {
            // Logged, not rethrown - see the method comment on enumeration.
            log.error("Could not email the password reset link for '{}'.", username, mailFailed);
        }
    }
}
