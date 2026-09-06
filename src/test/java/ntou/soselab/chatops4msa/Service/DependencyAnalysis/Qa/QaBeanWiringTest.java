package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Builds the Q&amp;A beans that have more than one constructor through a real Spring
 * context.
 *
 * Why this test exists: {@link ReportArchiveStore} shipped with two public
 * constructors and no {@code @Autowired}. Every unit test passed — they call the
 * constructor directly — and the application failed to start on the deployment
 * machine, where the only symptom was a bot that said "processing…" and never
 * answered. The full application context cannot be loaded in tests (it needs a
 * Discord token), so this pins the one thing that broke: Spring can pick a
 * constructor for these beans.
 */
public class QaBeanWiringTest {

    @Test
    void springCanInstantiateTheArchiveStoreFromItsAnnotatedConstructor() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ReportArchiveStore.class);
            ctx.refresh();
            assertNotNull(ctx.getBean(ReportArchiveStore.class));
        }
    }
}
