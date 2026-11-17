package io.opendbapi.core.endpoint;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Applies {@code opendbapi.script.*} to script compilation (spec §7.4, §22). */
@Component
class ScriptSettings {

    ScriptSettings(@Value("${opendbapi.script.groovy-enabled:true}") boolean enabled,
                   @Value("${opendbapi.script.timeout:2s}") Duration timeout) {
        CompiledTask.configureScripts(enabled, timeout.toMillis());
    }
}
