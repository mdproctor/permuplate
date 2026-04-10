package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Records a compliance audit event to multiple sinks with N contextual fields.
 *
 * <p>
 * Different regulatory frameworks demand different amounts of context per event.
 * A minimal GDPR log needs {@code (tenant, userId)}; PCI-DSS may require
 * {@code (tenant, userId, resourceId, cardLastFour, ipAddress)}. Collapsing these
 * into a {@code Map<String, String>} loses IDE navigation and makes it easy to
 * forget a required field. A distinct {@code AuditRecord{n}} class for each
 * compliance profile gives you a checked, named signature instead.
 *
 * <p>
 * {@code eventType} (what happened) and {@code severity} (how critical) are
 * fixed on every variant because every audit event must declare them. The N
 * contextual fields in between grow with the compliance requirement.
 *
 * <p>
 * Each generated class fans out to every audit sink (database, SIEM system,
 * message queue) by iterating {@link #sinks}.
 *
 * <p>
 * Example usage of the generated {@code AuditRecord4}:
 *
 * <pre>{@code
 * AuditRecord4 audit = new AuditRecord4();
 * audit.writer4 = (tenantId, userId, resourceId, sink) -> ((AuditSink) sink).write(tenantId, userId, resourceId);
 * audit.sinks = List.of(database, siemConnector, messageBus);
 * audit.record("RECORD_DELETED", tenantId, userId, resourceId, "WARN");
 * }</pre>
 */
@Permute(varName = "i", from = "3", to = "6", className = "AuditRecord${i}")
public class AuditRecord2 {

    /**
     * The writer function: dispatches the N-1 context fields plus one sink to
     * the underlying audit infrastructure. Renamed to {@code writer{i}}.
     */
    private @PermuteDeclr(type = "Callable${i}", name = "writer${i}") Callable2 writer2;

    /** The audit sinks to write to (database, SIEM system, message queue, …). */
    private List<Object> sinks;

    /**
     * Writes the audit event to every sink in {@link #sinks}.
     *
     * @param eventType what occurred, e.g. {@code "USER_LOGIN"}, {@code "RECORD_DELETED"}
     * @param context1..context{i-1} compliance context fields: tenant, userId, resourceId…
     * @param severity how critical the event is: {@code "INFO"}, {@code "WARN"}, {@code "CRITICAL"}
     */
    public void record(
            String eventType,
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "context${j}") Object context1,
            String severity) {
        System.out.println("[" + severity + "] EVENT: " + eventType);
        for (@PermuteDeclr(type = "Object", name = "sink${i}")
        Object sink2 : sinks) {
            writer2.call(context1, sink2);
        }
    }
}
