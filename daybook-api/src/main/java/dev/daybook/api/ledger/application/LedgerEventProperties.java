package dev.daybook.api.ledger.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event topic settings, bound from {@code daybook.events.*}.
 *
 * @param accountEntriesTopic versioned topic name; a breaking event change means a new version
 * @param accountEntriesPartitions number of partitions, i.e. how many consumers can read in
 *     parallel. Can be raised later but never lowered, and raising it moves keys between
 *     partitions.
 */
@ConfigurationProperties("daybook.events")
public record LedgerEventProperties(
    @DefaultValue("daybook.account-entries.v1") String accountEntriesTopic,
    @DefaultValue("6") int accountEntriesPartitions) {}
