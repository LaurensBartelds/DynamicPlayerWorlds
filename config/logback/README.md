# Logback JSON encoder class names

Paper and Velocity bind SLF4J to their own Log4j2 stacks. The plugin jars do
**not** ship Logback as a logging backend; they use the platform's. Structured
fields still reach those backends through SLF4J MDC (`MdcKeys` / `MdcContext`)
and SLF4J key-value pairs (`EventLogger`).

The jars **do** shade `net.logstash.logback` (the Logstash encoder) under:

```text
nl.gzmn.playerworlds.libs.logstash
```

so that if an operator (or a future standalone process) configures Logback with
that encoder against the classes inside the plugin jar, the configuration must
name the **relocated** class:

| Context | Encoder class |
| --- | --- |
| Unshaded (tests, a plain Java process on the compile classpath) | `net.logstash.logback.encoder.LogstashEncoder` |
| Inside a shaded plugin jar | `nl.gzmn.playerworlds.libs.logstash.encoder.LogstashEncoder` |

See `logback-json-plugin.xml.example` for a minimal JSON console appender that
uses the relocated name.
