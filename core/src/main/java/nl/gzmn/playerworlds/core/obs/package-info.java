/**
 * Observability foundation (plan section 10, NFR-6).
 *
 * <p>{@link nl.gzmn.playerworlds.core.obs.MdcKeys} and
 * {@link nl.gzmn.playerworlds.core.obs.MdcContext} standardise structured log
 * fields. {@link nl.gzmn.playerworlds.core.obs.LogEvent} and
 * {@link nl.gzmn.playerworlds.core.obs.EventLogger} make NFR-6's event list a
 * typed enum so a misspelled name cannot vanish from a dashboard.
 *
 * <p>{@link nl.gzmn.playerworlds.core.obs.WorldsMetrics} holds the Micrometer
 * registry and the section 10.2 meter set;
 * {@link nl.gzmn.playerworlds.core.obs.PrometheusEndpoint} serves the scrape
 * text. {@link nl.gzmn.playerworlds.core.obs.CapabilityProbe} runs the section
 * 10.4 startup checks, including the reflink verdict that decides whether
 * MN-5a's snapshot copy is cheap or a full copy.
 *
 * <p>JSON encoding is the operator's Logback configuration. The plugin jar
 * relocates {@code net.logstash.logback} to
 * {@code nl.gzmn.playerworlds.libs.logstash}, so a LogstashEncoder class name in
 * a logback.xml that ships <em>inside</em> the jar must use the relocated name
 * — see {@code config/logback/}.
 */
@NullMarked
package nl.gzmn.playerworlds.core.obs;

import org.jspecify.annotations.NullMarked;
