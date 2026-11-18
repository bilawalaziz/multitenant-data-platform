package com.platform.ingestion.common;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities with application-assigned UUIDs. Marks instances as "new" until persisted so
 * Spring Data issues an INSERT instead of a SELECT + merge.
 */
@MappedSuperclass
public abstract class PersistableEntity implements Persistable<UUID> {

    @Transient
    private boolean newEntity = true;

    @Override
    public abstract UUID getId();

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
