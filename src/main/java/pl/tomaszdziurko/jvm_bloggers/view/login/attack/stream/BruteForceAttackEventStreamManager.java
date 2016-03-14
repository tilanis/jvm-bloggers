package pl.tomaszdziurko.jvm_bloggers.view.login.attack.stream;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pl.tomaszdziurko.jvm_bloggers.mailing.MailSender;
import pl.tomaszdziurko.jvm_bloggers.settings.Setting;
import pl.tomaszdziurko.jvm_bloggers.settings.SettingKeys;
import pl.tomaszdziurko.jvm_bloggers.settings.SettingRepository;
import pl.tomaszdziurko.jvm_bloggers.view.login.attack.BruteForceAttackEvent;
import pl.tomaszdziurko.jvm_bloggers.view.login.attack.BruteForceAttackMailGenerator;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * @author Adam Dec
 */
@Slf4j
@Component
public class BruteForceAttackEventStreamManager {

    @VisibleForTesting
    public static final int MAILING_TIME_THROTTLE_IN_MINUTES = 1;

    private static final int INITIAL_CAPACITY = 256;

    private final ConcurrentMap<String, BruteForceAttackEventStream> streamMap = new ConcurrentHashMap<>(INITIAL_CAPACITY);

    @Autowired
    private MailSender mailSender;

    @Autowired
    private BruteForceAttackMailGenerator bruteForceAttackMailGenerator;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SettingRepository settingRepository;

    private String adminEmailAddress;

    /**
     * Builds and event stream which will emit items on predefined sampling time, event is mapped to Tuple and published to subscribers.
     * <ul>
     * <li>Please note that we are using a map of streams here to have dedicated streams per IP address.</li>
     * <li>Please note that onBackpressureLatest() seems to be redundant here but it is left only for case when our
     * subscriber will consume slower than we produce.</li>
     * </ul>
     *
     * @param clientAddress An IP address of the user that tries to attack us :)
     * @return An event stream that subscribers can subscribe on
     */
    public BruteForceAttackEventStream createEventStreamFor(final String clientAddress) {
        return streamMap.computeIfAbsent(clientAddress, key -> {
                final PublishSubject<BruteForceAttackEvent> subject = PublishSubject.create();
                final Observable<Pair<String, String>> observable = subject.sample(MAILING_TIME_THROTTLE_IN_MINUTES, TimeUnit.MINUTES, scheduler)
                        .map(this::buildTuple)
                        .observeOn(Schedulers.io())
                        .onBackpressureLatest();
                final BruteForceAttackEventStream stream = new BruteForceAttackEventStream(subject, observable);
                stream.subscribe(new BruteForceLoginAttackMailSubscriber(mailSender, adminEmailAddress));
                return stream;
            });
    }

    @PostConstruct
    public void init() {
        final Setting adminEmailAddressSetting = settingRepository.findByName(SettingKeys.ADMIN_EMAIL.toString());
        if (adminEmailAddressSetting == null) {
            throw new RuntimeException(SettingKeys.ADMIN_EMAIL.toString() + " not found in Setting table");
        }
        this.adminEmailAddress = adminEmailAddressSetting.getValue();
        log.debug("AdminEmailAddress={}", adminEmailAddress);
    }

    @PreDestroy
    public void destroy() {
        streamMap.forEach((ipAddress, stream) -> {
                stream.terminate();
                log.debug("Terminated stream for ipAddress={}", ipAddress);
            });
        streamMap.clear();
    }

    private Pair<String, String> buildTuple(final BruteForceAttackEvent event) {
        final String mailContent = bruteForceAttackMailGenerator.prepareMailContent(event);
        log.debug("Mail content\n{}", mailContent);

        final String mailTitle = bruteForceAttackMailGenerator.prepareMailTitle(event);
        log.debug("Mail title\n{}", mailTitle);

        return new ImmutablePair<>(mailTitle, mailContent);
    }
}