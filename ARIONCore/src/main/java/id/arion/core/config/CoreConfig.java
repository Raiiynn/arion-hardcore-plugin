package id.arion.core.config;

import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

public record CoreConfig(String world, Duration deathBanDuration, boolean requireSaber) {
    public CoreConfig {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world must not be blank");
        if (deathBanDuration == null || deathBanDuration.isNegative() || deathBanDuration.isZero()
                || deathBanDuration.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("deathban.duration-seconds must be 1..31536000");
        }
    }

    public static CoreConfig read(FileConfiguration config) {
        if (!config.isString("world") || !config.isInt("deathban.duration-seconds")
                || !config.isBoolean("integrations.saberfactions.required")) {
            throw new IllegalArgumentException("Invalid config types: world, deathban duration or Saber required");
        }
        return new CoreConfig(config.getString("world"),
                Duration.ofSeconds(config.getInt("deathban.duration-seconds")),
                config.getBoolean("integrations.saberfactions.required"));
    }
}
