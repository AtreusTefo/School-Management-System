package com.example.tracker.service;

import com.example.tracker.exception.TooManyAttemptsException;
import com.example.tracker.model.LoginAttempt;
import com.example.tracker.repository.LoginAttemptRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * HOW MANY TIMES SIGNING IN MAY FAIL BEFORE IT STOPS BEING ANSWERED.
 *
 * The whole rule lives here. AuthController calls three methods on it in
 * order - check, then record or clear - and decides nothing itself; what the
 * limits are, what window they apply over, and what being over one means are
 * all this class's, so they hold for anything that ever authenticates.
 *
 * WHAT IT DOES NOT DO, AND WHY THAT IS DELIBERATE
 * -----------------------------------------------
 * It does not lock an account. There is no flag on AppUser, nothing an
 * administrator has to unlock, and no state that outlives the window. Being
 * throttled is computed by counting rows in the last few minutes and stops
 * being true on its own.
 *
 * That matters because a username-keyed limit is, unavoidably, a way to make
 * somebody else's sign-in fail: anybody who knows a student's id can spend ten
 * guesses on it. A PERSISTENT lock would turn that into a denial of service
 * with an administrator in the loop to undo it, once per victim. A sliding
 * window bounds the damage at the window's length and needs nobody.
 *
 * THE ANSWER IS THE SAME WHATEVER WAS TRIED. Failures are counted for any
 * username, real or invented, so a guess at a real id and a guess at an
 * invented one are throttled alike. Counting only real accounts would have
 * made the difference between 429 and 401 a reliable test for whether a
 * sign-in id exists.
 */
@Service
@Transactional(readOnly = true)
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);

    /** Matches app_user.username and login_attempt.username. */
    private static final int USERNAME_COLUMN_LENGTH = 50;

    /** Longest an IPv6 address can be written as, including an IPv4 tail. */
    private static final int IP_COLUMN_LENGTH = 45;

    /** Used when the container cannot tell us what a request carried. */
    private static final String UNKNOWN = "unknown";

    /**
     * One sentence, for every refusal.
     *
     * It does not say which limit was hit, how many attempts have been made,
     * how many remain, or when the window reopens. Each of those is a number
     * an attacker would otherwise get for free to tune against, and the first
     * two would leak whether the username was worth attacking at all.
     */
    private static final String TOO_MANY =
            "Too many sign-in attempts. Please wait a few minutes and try again.";

    private final LoginAttemptRepository attempts;

    /**
     * How far back a failure still counts. Both limits below are expressed
     * over this one window, so widening it tightens them together rather than
     * needing two numbers changed consistently.
     */
    @Value("${tracker.login.window-minutes:15}")
    private int windowMinutes;

    /**
     * The limit that actually holds. Ten wrong passwords in a quarter of an
     * hour is well past anybody mistyping, and leaves an attacker roughly
     * forty guesses a day against one account.
     */
    @Value("${tracker.login.max-per-username:10}")
    private int maxPerUsername;

    /**
     * The limit against SPRAYING - one password tried against many accounts,
     * where no single username gets near its own limit.
     *
     * Deliberately loose, because a school is the NAT case: a computer lab
     * reaches this server from one public address, so this counts a whole
     * room's Monday-morning mistakes together. Tight enough to stop a
     * determined sprayer would lock out the room. Treat it as catching the
     * careless; the per-username limit is the one to rely on.
     */
    @Value("${tracker.login.max-per-ip:60}")
    private int maxPerIp;

    /**
     * How long a failure is kept after it has stopped counting. The throttle
     * never reads past the window, so this exists purely so that "was this
     * account attacked last night" is answerable.
     */
    @Value("${tracker.login.retain-hours:24}")
    private int retainHours;

    public LoginThrottleService(LoginAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * Refuse the attempt outright if either limit is already exceeded.
     *
     * Called BEFORE the password is checked, which is the point: an attempt
     * refused here costs one indexed count instead of a BCrypt verification,
     * so being under attack does not also mean being slow.
     */
    public void assertNotThrottled(String username, String ipAddress) {
        String key = normaliseUsername(username);
        String address = normalise(ipAddress, IP_COLUMN_LENGTH);
        Instant since = Instant.now().minus(Duration.ofMinutes(windowMinutes));

        long forUsername = attempts.countByUsernameAndAttemptedAtAfter(key, since);
        if (forUsername >= maxPerUsername) {
            log.warn("Sign-in throttled: {} failures for that username in the last {} minutes.",
                    forUsername, windowMinutes);
            throw new TooManyAttemptsException(TOO_MANY);
        }

        long forAddress = attempts.countByIpAddressAndAttemptedAtAfter(address, since);
        if (forAddress >= maxPerIp) {
            log.warn("Sign-in throttled: {} failures from {} in the last {} minutes.",
                    forAddress, address, windowMinutes);
            throw new TooManyAttemptsException(TOO_MANY);
        }
    }

    /**
     * Write down a failure.
     *
     * REQUIRES_NEW, and that is load-bearing. This is called as a sign-in is
     * about to end in a 401, and it must commit anyway - a failure that rolled
     * back with the request it belongs to would leave the counter permanently
     * at zero and the throttle permanently off, which is the kind of defect
     * that only shows up on the day it is needed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String username, String ipAddress) {
        attempts.save(new LoginAttempt(
                normaliseUsername(username),
                normalise(ipAddress, IP_COLUMN_LENGTH),
                Instant.now()));
    }

    /**
     * Forget a username's failures, on a sign-in that worked.
     *
     * Somebody who mistyped four times and then got it right should not spend
     * the rest of the window four failures from being refused.
     *
     * The address's history is deliberately NOT cleared. One correct sign-in
     * says nothing about the hundred failures that arrived from the same
     * address alongside it, and clearing it would hand a sprayer an obvious
     * way to reset the counter: keep one account they already own.
     */
    @Transactional
    public void clearFailures(String username) {
        attempts.deleteByUsername(normaliseUsername(username));
    }

    /**
     * Drop attempts old enough that nothing will read them again.
     *
     * Scheduled, so it does not run under the test profile - SchedulingConfig
     * is @Profile("!test") and explains why. LoginThrottleTest calls this
     * method directly, which is the better test anyway: whether the cutoff is
     * right is a property of the purge, not of the timer that invokes it.
     */
    @Scheduled(fixedDelayString = "${tracker.login.purge-interval-ms:3600000}",
               initialDelayString = "${tracker.login.purge-initial-delay-ms:300000}")
    @Transactional
    public int purgeOld() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(retainHours));
        int removed = attempts.deleteByAttemptedAtBefore(cutoff);
        if (removed > 0) {
            log.info("Purged {} sign-in attempts older than {} hours.", removed, retainHours);
        }
        return removed;
    }

    /**
     * Cap and tidy what gets stored.
     *
     * AuthController's LoginRequest already rejects anything longer than the
     * column, so in practice this truncates nothing. It is here because the
     * DTO guards one entry point and this rule has to hold for every caller -
     * the same both-ends reasoning the rest of the service layer follows.
     *
     * Lower-cased so that two spellings of one name are counted as the
     * attempts on a single account that they are.
     */
    private String normaliseUsername(String username) {
        return normalise(username, USERNAME_COLUMN_LENGTH).toLowerCase();
    }

    /**
     * A missing or over-long value becomes a single shared bucket rather than
     * being dropped. Dropping it would mean an attacker who can suppress the
     * value is not counted at all.
     */
    private String normalise(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * Where a request came from.
     *
     * Reads the remote address only - NOT X-Forwarded-For. Behind a proxy that
     * makes every request look like it came from the proxy, which is the safe
     * direction to be wrong in: the per-IP limit becomes one shared bucket and
     * the per-username limit is untouched. Trusting the header instead would
     * let anybody choose their own bucket key and opt out of per-IP counting
     * by varying it. server.forward-headers-strategy=framework, set for the
     * prod profile, is what makes the remote address correct when a trusted
     * proxy really is in front.
     */
    public String addressOf(HttpServletRequest request) {
        return request == null ? UNKNOWN : normalise(request.getRemoteAddr(), IP_COLUMN_LENGTH);
    }
}
